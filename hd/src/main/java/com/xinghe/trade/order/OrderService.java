package com.xinghe.trade.order;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import com.xinghe.trade.product.Product;
import com.xinghe.trade.product.ProductMapper;
import com.xinghe.trade.cart.CartItem;
import com.xinghe.trade.cart.CartItemMapper;
import com.xinghe.trade.inventory.ProductInventoryMapper;
import com.xinghe.trade.inventory.ProductInventory;
import com.xinghe.trade.payment.PaymentIdempotency;
import com.xinghe.trade.payment.PaymentIdempotencyMapper;
import com.xinghe.trade.logistics.LogisticsMapper;
import com.xinghe.trade.logistics.LogisticsRecord;
import com.xinghe.trade.messaging.OutboxService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.LocalDateTime;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 负责旧单笔下单、购物车结算和待支付订单的支付状态流转。 */
@Service
public class OrderService {
    private final RedisTemplate<String, Object> redis;
    private final RabbitTemplate rabbit;
    private final TradeOrderMapper orderMapper;
    private final TradeOrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final CartItemMapper cartItemMapper;
    private final ProductInventoryMapper inventoryMapper;
    private final LogisticsMapper logisticsMapper;
    private final PaymentIdempotencyMapper paymentIdempotencyMapper;
    private final OutboxService outbox;
    @Value("${xinghe.messaging.enabled:false}")
    private boolean messagingEnabled;

    @Autowired
    public OrderService(RedisTemplate<String, Object> redis, RabbitTemplate rabbit,
                        TradeOrderMapper orderMapper, TradeOrderItemMapper orderItemMapper,
                        ProductMapper productMapper, CartItemMapper cartItemMapper,
                        ProductInventoryMapper inventoryMapper, LogisticsMapper logisticsMapper,
                        PaymentIdempotencyMapper paymentIdempotencyMapper, OutboxService outbox) {
        this.redis = redis;
        this.rabbit = rabbit;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.cartItemMapper = cartItemMapper;
        this.inventoryMapper = inventoryMapper;
        this.logisticsMapper = logisticsMapper;
        this.paymentIdempotencyMapper = paymentIdempotencyMapper;
        this.outbox = outbox;
    }

    public OrderService(RedisTemplate<String, Object> redis, RabbitTemplate rabbit,
                        TradeOrderMapper orderMapper, TradeOrderItemMapper orderItemMapper,
                        ProductMapper productMapper, CartItemMapper cartItemMapper,
                        ProductInventoryMapper inventoryMapper, LogisticsMapper logisticsMapper,
                        PaymentIdempotencyMapper paymentIdempotencyMapper) {
        this(redis, rabbit, orderMapper, orderItemMapper, productMapper, cartItemMapper,
                inventoryMapper, logisticsMapper, paymentIdempotencyMapper, null);
    }

    /** 保留旧单笔下单流程的构造方式，便于既有调用测试与集成代码兼容。 */
    public OrderService(RedisTemplate<String, Object> redis, RabbitTemplate rabbit,
                        TradeOrderMapper orderMapper, TradeOrderItemMapper orderItemMapper,
                        ProductMapper productMapper) {
        this(redis, rabbit, orderMapper, orderItemMapper, productMapper, null, null, null, null, null);
    }

    /** 兼容结算测试和既有调用方使用的七依赖构造方式。 */
    public OrderService(RedisTemplate<String, Object> redis, RabbitTemplate rabbit,
                        TradeOrderMapper orderMapper, TradeOrderItemMapper orderItemMapper,
                        ProductMapper productMapper, CartItemMapper cartItemMapper,
                        ProductInventoryMapper inventoryMapper) {
        this(redis, rabbit, orderMapper, orderItemMapper, productMapper, cartItemMapper,
                inventoryMapper, null, null, null);
    }

    /** 保留阶段 C 完整构造方式；支付幂等 Mapper 缺省时仅供旧单元测试兼容。 */
    public OrderService(RedisTemplate<String, Object> redis, RabbitTemplate rabbit,
                        TradeOrderMapper orderMapper, TradeOrderItemMapper orderItemMapper,
                        ProductMapper productMapper, CartItemMapper cartItemMapper,
                        ProductInventoryMapper inventoryMapper, LogisticsMapper logisticsMapper) {
        this(redis, rabbit, orderMapper, orderItemMapper, productMapper, cartItemMapper,
                inventoryMapper, logisticsMapper, null, null);
    }

    /**
     * 根据购物车快照创建订单。
     * 价格、商品状态和库存均以服务端当前数据为准，所有订单与库存变更在同一事务中完成。
     */
    @Transactional
    public List<OrderResult> checkout(String userId) {
        // 保留旧调用方兼容性；HTTP 接口始终要求调用方提供幂等 Token。
        return checkout(userId, "legacy-" + UUID.randomUUID());
    }

    /** 使用用户范围内的幂等 Token 创建一批购物车订单。 */
    @Transactional
    public List<OrderResult> checkout(String userId, String token) {
        // 结算身份只来自请求头，并统一去除首尾空格，避免按不同字符串产生多份购物车。
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("用户 ID 不能为空");
        String normalizedUserId = userId.trim();
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Idempotency-Token 不能为空");
        String normalizedToken = token.trim();
        if (normalizedToken.length() > 128) throw new IllegalArgumentException("Idempotency-Token 不能超过 128 个字符");
        String idempotencyKey = "checkout:idempotent:" + normalizedUserId + ":" + normalizedToken;
        Boolean firstRequest = redis.opsForValue().setIfAbsent(idempotencyKey, "PROCESSING", Duration.ofMinutes(30));
        if (firstRequest == null) throw new IllegalStateException("结算幂等服务暂不可用，请稍后重试");
        if (Boolean.FALSE.equals(firstRequest)) {
            Object storedOrderNo = redis.opsForValue().get(idempotencyKey);
            if (storedOrderNo == null || "PROCESSING".equals(String.valueOf(storedOrderNo))) {
                throw new IllegalStateException("结算正在处理中，请稍后重试");
            }
            return loadCheckoutResults(normalizedUserId, String.valueOf(storedOrderNo));
        }

        try {
            // 锁定完整购物车快照；后续任一商品校验或库存锁定失败都会回滚本次结算。
            List<CartItem> cartItems = cartItemMapper.selectForCheckout(normalizedUserId);
            if (cartItems.isEmpty()) throw new IllegalStateException("购物车为空");
            LocalDateTime now = LocalDateTime.now();
            // 父订单保存整笔结算总额，子订单按店铺聚合以承载发货和库存生命周期。
            String parentOrderNo = newOrderNo();
            Map<Long, List<CartItem>> itemsByStore = new LinkedHashMap<>();
            Map<Long, Product> productsBySku = new LinkedHashMap<>();
            BigDecimal totalAmount = BigDecimal.ZERO;
            ArrayList<OrderResult> results = new ArrayList<>();
            for (CartItem cartItem : cartItems) {
                // 重新查询商品而不是使用购物车快照，保证下架商品和旧价格不能继续结算。
                if (cartItem.getQuantity() == null || cartItem.getQuantity() < 1 || cartItem.getQuantity() > 99) {
                    throw new IllegalArgumentException("购物车商品数量无效");
                }
                Product product = productMapper.selectById(cartItem.getSkuId());
                if (product == null || !"ON_SALE".equals(product.getStatus())) {
                    throw new IllegalArgumentException("商品不存在或已下架: " + cartItem.getSkuId());
                }
                if (product.getPrice() == null || product.getPrice().signum() < 0) {
                    throw new IllegalStateException("商品价格配置异常");
                }
                if (product.getStoreId() == null) throw new IllegalStateException("商品未关联店铺");
                itemsByStore.computeIfAbsent(product.getStoreId(), ignored -> new ArrayList<>()).add(cartItem);
                productsBySku.put(product.getId(), product);
                totalAmount = totalAmount.add(product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
            }

            TradeOrder parent = new TradeOrder();
            parent.setOrderNo(parentOrderNo);
            parent.setUserId(normalizedUserId);
            parent.setSkuId(null);
            parent.setQuantity(null);
            parent.setAmount(totalAmount);
            parent.setStatus("PENDING_PAYMENT");
            parent.setCreatedAt(now);
            parent.setUpdatedAt(now);
            if (orderMapper.insert(parent) != 1) throw new IllegalStateException("父订单写入失败");

            for (Map.Entry<Long, List<CartItem>> entry : itemsByStore.entrySet()) {
                String childOrderNo = newOrderNo();
                BigDecimal storeAmount = BigDecimal.ZERO;
                TradeOrder child = new TradeOrder();
                child.setOrderNo(childOrderNo);
                child.setParentOrderNo(parentOrderNo);
                child.setUserId(normalizedUserId);
                child.setStoreId(entry.getKey());
                child.setAmount(BigDecimal.ZERO);
                child.setStatus("PENDING_PAYMENT");
                child.setCreatedAt(now);
                child.setUpdatedAt(now);
                if (orderMapper.insert(child) != 1) throw new IllegalStateException("店铺子订单写入失败");

                for (CartItem cartItem : entry.getValue()) {
                    Product product = productsBySku.get(cartItem.getSkuId());
                    lockInventory(product.getId(), cartItem.getQuantity());
                    BigDecimal lineAmount = product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity()));
                    TradeOrderItem item = new TradeOrderItem();
                    item.setOrderNo(childOrderNo);
                    item.setSkuId(product.getId());
                    item.setQuantity(cartItem.getQuantity());
                    item.setUnitPrice(product.getPrice());
                    item.setLineAmount(lineAmount);
                    if (orderItemMapper.insert(item) != 1) throw new IllegalStateException("订单明细写入失败");
                    storeAmount = storeAmount.add(lineAmount);
                }
                child.setAmount(storeAmount);
                if (orderMapper.updateById(child) != 1) throw new IllegalStateException("子订单金额更新失败");
                results.add(new OrderResult(childOrderNo, child.getStatus()));
                if (messagingEnabled) {
                    recordTimeout(childOrderNo, 15 * 60 * 1000L);
                }
            }

            // 订单和库存全部落库后仅删除快照行，保留结算期间新增的购物车商品。
            List<Long> snapshotIds = cartItems.stream().map(CartItem::getId).toList();
            if (cartItemMapper.deleteSnapshot(normalizedUserId, snapshotIds) != snapshotIds.size()) {
                throw new IllegalStateException("购物车快照已发生变化，请重试");
            }
            results.add(0, new OrderResult(parentOrderNo, "PENDING_PAYMENT"));
            redis.opsForValue().set(idempotencyKey, parentOrderNo, Duration.ofHours(24));
            return List.copyOf(results);
        } catch (RuntimeException exception) {
            redis.delete(idempotencyKey);
            throw exception;
        }
    }

    /** 从已完成的结算父订单重建幂等响应。 */
    private List<OrderResult> loadCheckoutResults(String userId, String parentOrderNo) {
        TradeOrder parent = orderMapper.selectById(parentOrderNo);
        if (parent == null || !userId.equals(parent.getUserId()) || parent.getParentOrderNo() != null) {
            throw new IllegalStateException("结算幂等记录无效");
        }
        List<TradeOrder> children = orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getParentOrderNo, parentOrderNo));
        ArrayList<OrderResult> results = new ArrayList<>();
        results.add(new OrderResult(parentOrderNo, parent.getStatus()));
        children.forEach(child -> results.add(new OrderResult(child.getOrderNo(), child.getStatus())));
        return List.copyOf(results);
    }


    /** 按用户归属读取父订单以及其店铺子订单和商品明细。 */
    public OrderView detail(String userId, String orderNo) {
        TradeOrder order = requireOwnedOrder(userId, orderNo);
        List<TradeOrder> children = order.getParentOrderNo() == null
                ? orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getParentOrderNo, orderNo))
                : List.of(order);
        // 兼容阶段 A 的独立 SKU 订单，其明细直接挂在订单自身编号下。
        if (children.isEmpty() && order.getSkuId() != null) children = List.of(order);
        List<String> childNos = children.stream().map(TradeOrder::getOrderNo).toList();
        List<TradeOrderItem> items = childNos.isEmpty() ? List.of() : orderItemMapper.selectList(
                new LambdaQueryWrapper<TradeOrderItem>().in(TradeOrderItem::getOrderNo, childNos));
        return new OrderView(order, List.copyOf(children), List.copyOf(items));
    }

    /** 取消用户自己的待支付订单，并在同一事务内恢复所有子订单锁定库存。 */
    @Transactional
    public void cancel(String userId, String orderNo) {
        TradeOrder order = requireOwnedOrder(userId, orderNo);
        if (order.getParentOrderNo() == null && order.getSkuId() == null &&
                !"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new IllegalStateException("当前订单不可取消");
        }
        String parentOrderNo = order.getParentOrderNo() == null ? orderNo : order.getParentOrderNo();
        TradeOrder lockedParent = orderMapper.selectForUpdate(parentOrderNo);
        if (lockedParent != null) order = lockedParent;
        List<TradeOrder> targets = orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getParentOrderNo, parentOrderNo));
        // 为兼容早期独立单 SKU 订单，只有没有子订单时才取消订单自身。
        if (targets.isEmpty() && order.getParentOrderNo() == null && order.getSkuId() != null) targets = List.of(order);
        if (targets.isEmpty() || targets.stream().anyMatch(target -> !"PENDING_PAYMENT".equals(target.getStatus()))) {
            throw new IllegalStateException("当前订单不可取消");
        }
        for (TradeOrder target : targets) cancelChildAndRelease(target);
        if ((order.getParentOrderNo() == null && order.getSkuId() == null) || order.getParentOrderNo() != null) {
            updateParentStatus(parentOrderNo);
        }
    }

    /** 校验店铺归属后登记物流并将已支付子订单标记为已发货。 */
    @Transactional
    public LogisticsRecord ship(Long storeId, String orderNo, String company, String trackingNo) {
        TradeOrder order = orderMapper.selectById(orderNo);
        if (order == null || order.getParentOrderNo() == null) throw new IllegalArgumentException("店铺子订单不存在");
        if (storeId == null || !storeId.equals(order.getStoreId())) throw new SecurityException("无权操作该店铺订单");
        if (!"PAID".equals(order.getStatus())) throw new IllegalStateException("仅已支付订单可以发货");
        if (logisticsMapper.selectCount(new LambdaQueryWrapper<LogisticsRecord>().eq(LogisticsRecord::getOrderNo, orderNo)) > 0) {
            throw new IllegalStateException("订单已发货");
        }
        LogisticsRecord logistics = new LogisticsRecord();
        logistics.setOrderNo(orderNo);
        logistics.setCompany(company.trim());
        logistics.setTrackingNo(trackingNo.trim());
        logistics.setStatus("SHIPPED");
        logistics.setDescription("商家已发货");
        logistics.setUpdatedAt(LocalDateTime.now());
        if (logisticsMapper.insert(logistics) != 1) throw new IllegalStateException("物流信息写入失败");
        order.setStatus("SHIPPED");
        order.setUpdatedAt(LocalDateTime.now());
        if (orderMapper.updateById(order) != 1) throw new IllegalStateException("订单发货状态更新失败");
        updateParentStatus(order.getParentOrderNo());
        return logistics;
    }

    /** 根据请求身份读取订单，避免查询和取消接口越权访问。 */
    private TradeOrder requireOwnedOrder(String userId, String orderNo) {
        if (userId == null || userId.isBlank() || orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("用户和订单号不能为空");
        }
        TradeOrder order = orderMapper.selectById(orderNo.trim());
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if (!userId.trim().equals(order.getUserId())) throw new SecurityException("无权访问该订单");
        return order;
    }

    /** 以条件更新保证取消与支付、超时消息竞争时只释放一次库存。 */
    private void cancelChildAndRelease(TradeOrder child) {
        TradeOrder update = new TradeOrder();
        update.setOrderNo(child.getOrderNo());
        update.setStatus("CANCELLED");
        update.setUpdatedAt(LocalDateTime.now());
        if (orderMapper.update(update, new LambdaUpdateWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderNo, child.getOrderNo())
                .eq(TradeOrder::getStatus, "PENDING_PAYMENT")) != 1) {
            throw new IllegalStateException("订单状态已变化，请刷新后重试");
        }
        List<TradeOrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<TradeOrderItem>()
                .eq(TradeOrderItem::getOrderNo, child.getOrderNo()));
        if (items.isEmpty() && child.getSkuId() != null && child.getQuantity() != null) {
            releaseInventory(child.getSkuId(), child.getQuantity());
        }
        for (TradeOrderItem item : items) {
            releaseInventory(item.getSkuId(), item.getQuantity());
        }
    }

    /** 所有店铺子订单到达终态后，同步更新父订单汇总状态。 */
    private void updateParentStatus(String parentOrderNo) {
        List<TradeOrder> children = orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getParentOrderNo, parentOrderNo));
        if (children.isEmpty()) return;
        String status = OrderStatus.fromChildren(children);
        TradeOrder parent = new TradeOrder();
        parent.setOrderNo(parentOrderNo);
        parent.setStatus(status);
        parent.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(parent);
    }
    @Transactional
    public OrderResult create(OrderController.CreateOrderRequest request) {
        // 校验下单参数、服务端重算金额、锁定数据库库存并落库订单明细。
        if (request == null || request.userId() == null || request.userId().isBlank()) throw new IllegalArgumentException("用户 ID 不能为空");
        if (request.skuId() == null) throw new IllegalArgumentException("商品 ID 不能为空");
        if (request.quantity() == null || request.quantity() <= 0) throw new IllegalArgumentException("购买数量必须大于 0");
        if (request.amount() == null || request.amount().signum() < 0) throw new IllegalArgumentException("订单金额不能为负数");

        // 阶段 A：服务端根据在售商品价格重新计算订单金额，避免客户端篡改金额。
        Product product = productMapper.selectById(request.skuId());
        if (product == null || !"ON_SALE".equals(product.getStatus())) {
            throw new IllegalArgumentException("商品不存在或已下架");
        }
        if (product.getPrice() == null || product.getPrice().signum() < 0) {
            throw new IllegalStateException("商品价格配置异常");
        }
        java.math.BigDecimal expectedAmount = product.getPrice()
                .multiply(java.math.BigDecimal.valueOf(request.quantity()))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        if (request.amount().compareTo(expectedAmount) != 0) {
            throw new IllegalArgumentException("订单金额与商品价格不一致");
        }
        if (product.getStoreId() == null) throw new IllegalStateException("商品未关联店铺");
        if (inventoryMapper == null) throw new IllegalStateException("库存服务未配置");
        String orderNo = newOrderNo();
        lockInventory(request.skuId(), request.quantity());

        LocalDateTime now = LocalDateTime.now();
        try {
            TradeOrder order = new TradeOrder();
            order.setOrderNo(orderNo);
            order.setUserId(request.userId());
            order.setSkuId(request.skuId());
            order.setQuantity(request.quantity());
            order.setStoreId(product.getStoreId());
            order.setAmount(expectedAmount);
            order.setStatus("PENDING_PAYMENT");
            order.setCreatedAt(now);
            order.setUpdatedAt(now);
            // 阶段 A：数据库写入必须确认影响行数，否则抛错触发事务回滚和库存补偿。
            if (orderMapper.insert(order) != 1) throw new IllegalStateException("订单写入失败");

            TradeOrderItem item = new TradeOrderItem();
            item.setOrderNo(orderNo);
            item.setSkuId(request.skuId());
            item.setQuantity(request.quantity());
            item.setUnitPrice(product.getPrice());
            item.setLineAmount(expectedAmount);
            if (orderItemMapper.insert(item) != 1) throw new IllegalStateException("订单明细写入失败");

            if (messagingEnabled) recordTimeout(orderNo, 30_000L);
        } catch (RuntimeException exception) {
            releaseInventory(request.skuId(), request.quantity());
            throw exception;
        }
        return new OrderResult(orderNo, "PENDING_PAYMENT");
    }

    /** 支付待支付订单，并将结算订单的锁定库存确认扣减。 */
    @Transactional(timeout = 30)
    public OrderResult pay(String orderNo, String userId, String token) {
        // 生产路径完全由 MySQL Token 唯一键和行锁保证幂等，Redis 仅保留给旧构造器兼容测试。
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Idempotency-Token 不能为空");
        if (paymentIdempotencyMapper != null) return payWithDatabaseToken(orderNo, userId, token);
        TradeOrder order = orderMapper.selectById(orderNo);
        if (order == null) throw new IllegalArgumentException("订单不存在");
        if (userId == null || userId.isBlank() || !userId.equals(order.getUserId())) {
            throw new SecurityException("无权支付该订单");
        }
        if (order.getParentOrderNo() != null) throw new IllegalStateException("请支付父订单");
        if ("PAID".equals(order.getStatus())) return new OrderResult(orderNo, "PAID");
        if (!"PENDING_PAYMENT".equals(order.getStatus())) throw new IllegalStateException("当前订单不可支付");
        String key = "pay:idempotent:" + token;
        Boolean first = redis.opsForValue().setIfAbsent(key, orderNo, Duration.ofMinutes(15));
        // 阶段 A：Redis 未返回幂等锁结果时立即失败关闭，禁止绕过重复支付保护。
        if (first == null) throw new IllegalStateException("支付幂等服务暂不可用，请稍后重试");
        if (Boolean.FALSE.equals(first)) {
            Object boundOrderNo = redis.opsForValue().get(key);
            if (boundOrderNo != null && !orderNo.equals(String.valueOf(boundOrderNo))) {
                throw new IllegalArgumentException("Idempotency-Token 已被其他订单使用");
            }
            TradeOrder latest = orderMapper.selectById(orderNo);
            if (latest != null && "PAID".equals(latest.getStatus())) {
                return new OrderResult(orderNo, "PAID (重复请求已幂等返回)");
            }
            if (latest != null && "PENDING_PAYMENT".equals(latest.getStatus())) {
                throw new IllegalStateException("支付处理中，请稍后重试");
            }
            throw new IllegalStateException("当前订单不可支付");
        }
        try {
            // 阶段 A：使用订单状态条件更新，和超时取消形成互斥状态迁移。
            TradeOrder update = new TradeOrder();
            update.setOrderNo(orderNo);
            update.setStatus("PAID");
            update.setUpdatedAt(LocalDateTime.now());
            int updated = orderMapper.update(update, new LambdaUpdateWrapper<TradeOrder>()
                    .eq(TradeOrder::getOrderNo, orderNo)
                    .eq(TradeOrder::getStatus, "PENDING_PAYMENT"));
            if (updated == 1) {
                if (order.getParentOrderNo() == null && order.getSkuId() == null) {
                    // 父订单支付成功后统一确认所有子订单库存，子订单仍保持各自的发货状态流转。
                    List<TradeOrder> children = orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                            .eq(TradeOrder::getParentOrderNo, orderNo));
                    if (children.isEmpty()) throw new IllegalStateException("父订单缺少店铺子订单");
                    for (TradeOrder child : children) {
                        TradeOrder childUpdate = new TradeOrder();
                        childUpdate.setOrderNo(child.getOrderNo());
                        childUpdate.setStatus("PAID");
                        childUpdate.setUpdatedAt(LocalDateTime.now());
                        if (orderMapper.update(childUpdate, new LambdaUpdateWrapper<TradeOrder>()
                                .eq(TradeOrder::getOrderNo, child.getOrderNo())
                                .eq(TradeOrder::getStatus, "PENDING_PAYMENT")) != 1) {
                            throw new IllegalStateException("店铺子订单状态已变化");
                        }
                        confirmOrderInventory(child.getOrderNo());
                    }
                } else if (order.getParentOrderNo() != null) {
                    confirmOrderInventory(orderNo);
                    updateParentStatus(order.getParentOrderNo());
                }
                return new OrderResult(orderNo, "PAID");
            }

            // 阶段 A：更新失败后重新读取最终状态，禁止把已取消订单误报为已支付。
            TradeOrder latest = orderMapper.selectById(orderNo);
            if (latest != null && "PAID".equals(latest.getStatus())) return new OrderResult(orderNo, "PAID");
            throw new IllegalStateException("订单已取消，支付失败");
        } catch (RuntimeException exception) {
            // 数据库更新失败时释放本次抢到的幂等 Token，允许客户端安全重试。
            redis.delete(key);
            throw exception;
        }
    }

    /** 支付 Token 与订单状态在同一事务中锁定，重复请求等待首个事务提交后读取最终结果。 */
    private OrderResult payWithDatabaseToken(String orderNo, String userId, String token) {
        PaymentIdempotency request = new PaymentIdempotency();
        request.setToken(token.trim());
        request.setOrderNo(orderNo);
        request.setUserId(userId);
        request.setStatus("PROCESSING");
        paymentIdempotencyMapper.insertProcessing(request);
        PaymentIdempotency stored = paymentIdempotencyMapper.selectForUpdate(token.trim());
        if (stored == null) throw new IllegalStateException("支付幂等记录写入失败");
        if (!orderNo.equals(stored.getOrderNo()) || !userId.equals(stored.getUserId())) {
            throw new IllegalStateException("Idempotency-Token 已被其他订单或用户使用");
        }
        if ("SUCCEEDED".equals(stored.getStatus())) {
            return new OrderResult(orderNo, stored.getResultStatus());
        }
        if (!"PROCESSING".equals(stored.getStatus())) {
            throw new IllegalStateException(stored.getErrorMessage() == null ? "支付不可用" : stored.getErrorMessage());
        }

        TradeOrder lockedOrder = orderMapper.selectForUpdate(orderNo);
        if (lockedOrder == null) throw new IllegalArgumentException("订单不存在");
        if (!userId.equals(lockedOrder.getUserId())) throw new SecurityException("无权支付该订单");
        if (lockedOrder.getParentOrderNo() != null) throw new IllegalStateException("请支付父订单");
        if ("PAID".equals(lockedOrder.getStatus())) {
            PaymentIdempotency succeeded = paymentIdempotencyMapper.selectSucceededByOrderNo(orderNo);
            if (succeeded != null && stored.getToken().equals(succeeded.getToken())) return new OrderResult(orderNo, "PAID");
            throw new IllegalStateException("订单已支付，不能重复确认");
        }
        if (!"PENDING_PAYMENT".equals(lockedOrder.getStatus())) {
            throw new IllegalStateException("当前订单不可支付");
        }

        TradeOrder update = new TradeOrder();
        update.setOrderNo(orderNo);
        update.setStatus("PAID");
        update.setUpdatedAt(LocalDateTime.now());
        if (orderMapper.update(update, new LambdaUpdateWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderNo, orderNo)
                .eq(TradeOrder::getStatus, "PENDING_PAYMENT")) != 1) {
            throw new IllegalStateException("订单状态已变化，请重试");
        }
        if (lockedOrder.getSkuId() != null) {
            confirmOrderInventory(orderNo);
        } else {
            List<TradeOrder> children = orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                    .eq(TradeOrder::getParentOrderNo, orderNo));
            if (children.isEmpty()) throw new IllegalStateException("父订单缺少店铺子订单");
            for (TradeOrder child : children) {
                TradeOrder childUpdate = new TradeOrder();
                childUpdate.setOrderNo(child.getOrderNo());
                childUpdate.setStatus("PAID");
                childUpdate.setUpdatedAt(LocalDateTime.now());
                if (orderMapper.update(childUpdate, new LambdaUpdateWrapper<TradeOrder>()
                        .eq(TradeOrder::getOrderNo, child.getOrderNo())
                        .eq(TradeOrder::getStatus, "PENDING_PAYMENT")) != 1) {
                    throw new IllegalStateException("店铺子订单状态已变化");
                }
                confirmOrderInventory(child.getOrderNo());
            }
        }
        markPaymentSucceeded(stored, "PAID");
        return new OrderResult(orderNo, "PAID");
    }

    private void markPaymentSucceeded(PaymentIdempotency record, String resultStatus) {
        record.setStatus("SUCCEEDED");
        record.setResultStatus(resultStatus);
        record.setErrorMessage(null);
        record.setUpdatedAt(LocalDateTime.now());
        if (paymentIdempotencyMapper.updateById(record) != 1) {
            throw new IllegalStateException("支付幂等记录更新失败");
        }
    }

    private void recordTimeout(String orderNo, long expiresInMs) {
        if (outbox == null) {
            rabbit.convertAndSend("xinghe.order.exchange", "order.timeout", orderNo, message -> {
                message.getMessageProperties().setExpiration(String.valueOf(expiresInMs));
                return message;
            });
            return;
        }
        outbox.record("ORDER_TIMEOUT", "order.timeout",
                Map.of("orderNo", orderNo, "expiresAt", LocalDateTime.now().plusNanos(expiresInMs * 1_000_000L)));
    }

    /** 将指定子订单的所有锁定库存确认为已售出。 */
    private void confirmOrderInventory(String orderNo) {
        for (TradeOrderItem item : orderItemMapper.selectList(new LambdaQueryWrapper<TradeOrderItem>()
                .eq(TradeOrderItem::getOrderNo, orderNo))) {
            confirmInventory(item.getSkuId(), item.getQuantity());
        }
    }

    /** 读取版本后条件更新；冲突最多重试五次，库存不足不重试。 */
    private void lockInventory(Long skuId, int quantity) {
        for (int attempt = 0; attempt < 5; attempt++) {
            ProductInventory inventory = inventoryMapper.selectInventory(skuId);
            if (inventory == null) {
                if (inventoryMapper.lockAvailable(skuId, quantity) == 1) return;
                throw new IllegalStateException("商品库存不足");
            }
            long version = inventory.getVersion() == null ? 0L : inventory.getVersion();
            if (inventory.getAvailableQuantity() == null || inventory.getAvailableQuantity() < quantity) {
                throw new IllegalStateException("商品库存不足");
            }
            if (inventoryMapper.lockAvailable(skuId, quantity, version) == 1) return;
        }
        throw new IllegalStateException("库存并发冲突，请稍后重试");
    }

    private void confirmInventory(Long skuId, int quantity) {
        for (int attempt = 0; attempt < 5; attempt++) {
            ProductInventory inventory = inventoryMapper.selectInventory(skuId);
            if (inventory == null) {
                if (inventoryMapper.confirmLocked(skuId, quantity) == 1) return;
                throw new IllegalStateException("锁定库存状态异常");
            }
            long version = inventory.getVersion() == null ? 0L : inventory.getVersion();
            if (inventory.getLockedQuantity() == null || inventory.getLockedQuantity() < quantity) {
                throw new IllegalStateException("锁定库存状态异常");
            }
            if (inventoryMapper.confirmLocked(skuId, quantity, version) == 1) return;
        }
        throw new IllegalStateException("库存并发冲突，请稍后重试");
    }

    private void releaseInventory(Long skuId, int quantity) {
        for (int attempt = 0; attempt < 5; attempt++) {
            ProductInventory inventory = inventoryMapper.selectInventory(skuId);
            if (inventory == null) {
                if (inventoryMapper.releaseLocked(skuId, quantity) == 1) return;
                throw new IllegalStateException("锁定库存状态异常");
            }
            long version = inventory.getVersion() == null ? 0L : inventory.getVersion();
            if (inventory.getLockedQuantity() == null || inventory.getLockedQuantity() < quantity) {
                throw new IllegalStateException("锁定库存状态异常");
            }
            if (inventoryMapper.releaseLocked(skuId, quantity, version) == 1) return;
        }
        throw new IllegalStateException("库存并发冲突，请稍后重试");
    }

    /** 生成订单编号，供购物车结算批次和单笔订单共用。 */
    private String newOrderNo() {
        return "XH" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /** 订单详情包含所查订单、店铺子订单列表和对应的商品价格快照。 */
    public record OrderView(TradeOrder order, List<TradeOrder> childOrders, List<TradeOrderItem> items) {}

    /** 返回结算父订单和店铺子订单编号及其当前状态。 */
    public record OrderResult(String orderNo, String status) {}
}
