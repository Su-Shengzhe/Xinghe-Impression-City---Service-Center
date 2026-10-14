package com.xinghe.trade.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghe.trade.inventory.ProductInventory;
import com.xinghe.trade.inventory.ProductInventoryMapper;
import com.xinghe.trade.store.Store;
import com.xinghe.trade.store.StoreMapper;
import com.xinghe.trade.messaging.OutboxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理店铺商品资料和商品对应的数据库库存。 */
@Service
public class ProductManagementService {
    private final ProductMapper productMapper;
    private final StoreMapper storeMapper;
    private final ProductInventoryMapper inventoryMapper;
    private final OutboxService outbox;

    /** 注入商品、店铺与库存 mapper，确保管理操作遵循现有数据库模型。 */
    public ProductManagementService(ProductMapper productMapper, StoreMapper storeMapper,
                                    ProductInventoryMapper inventoryMapper) {
        this(productMapper, storeMapper, inventoryMapper, null);
    }

    @Autowired
    public ProductManagementService(ProductMapper productMapper, StoreMapper storeMapper,
                                    ProductInventoryMapper inventoryMapper, OutboxService outbox) {
        this.productMapper = productMapper;
        this.storeMapper = storeMapper;
        this.inventoryMapper = inventoryMapper;
        this.outbox = outbox;
    }

    /** 创建商品并初始化其可售库存；商品和库存记录在同一事务提交。 */
    @Transactional
    public Product create(Long storeId, ProductRequest request) {
        requireStore(storeId);
        validateProduct(request);
        if (productMapper.selectById(request.skuId()) != null) throw new IllegalArgumentException("商品 SKU 已存在");
        Product product = new Product();
        product.setId(request.skuId());
        product.setStoreId(storeId);
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStatus("ON_SALE");
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        if (productMapper.insert(product) != 1) throw new IllegalStateException("商品创建失败");
        ProductInventory inventory = new ProductInventory();
        inventory.setSkuId(product.getId());
        inventory.setAvailableQuantity(request.availableQuantity());
        inventory.setLockedQuantity(0);
        inventory.setVersion(0L);
        inventory.setSoldQuantity(0);
        inventory.setUpdatedAt(LocalDateTime.now());
        if (inventoryMapper.insert(inventory) != 1) throw new IllegalStateException("商品库存初始化失败");
        recordIndexEvent(product.getId());
        return product;
    }

    /** 修改所属店铺商品资料，不允许通过编辑请求迁移商品店铺或覆盖库存。 */
    @Transactional
    public Product update(Long storeId, Long skuId, ProductUpdateRequest request) {
        Product product = requireOwnedProduct(storeId, skuId);
        validateProductDetails(request);
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setUpdatedAt(LocalDateTime.now());
        if (productMapper.updateById(product) != 1) throw new IllegalStateException("商品更新失败");
        recordIndexEvent(product.getId());
        return product;
    }

    /** 只允许将有效商品状态切换为在售或下架。 */
    @Transactional
    public Product setStatus(Long storeId, Long skuId, String status) {
        Product product = requireOwnedProduct(storeId, skuId);
        if (!"ON_SALE".equals(status) && !"OFF_SALE".equals(status)) {
            throw new IllegalArgumentException("商品状态只能是 ON_SALE 或 OFF_SALE");
        }
        product.setStatus(status);
        product.setUpdatedAt(LocalDateTime.now());
        if (productMapper.updateById(product) != 1) throw new IllegalStateException("商品状态更新失败");
        recordIndexEvent(product.getId());
        return product;
    }

    /** 设置可用库存，同时保留未支付订单已经占用的锁定数量。 */
    @Transactional
    public ProductInventory setInventory(Long storeId, Long skuId, Integer availableQuantity) {
        requireOwnedProduct(storeId, skuId);
        if (availableQuantity == null || availableQuantity < 0) throw new IllegalArgumentException("可用库存不能为负数");
        for (int attempt = 0; attempt < 5; attempt++) {
            ProductInventory inventory = inventoryMapper.selectInventory(skuId);
            if (inventory == null) throw new IllegalStateException("商品库存记录不存在");
            long version = inventory.getVersion() == null ? 0L : inventory.getVersion();
            if (inventoryMapper.updateAvailable(skuId, availableQuantity, version) == 1) {
                return inventoryMapper.selectById(skuId);
            }
        }
        throw new IllegalStateException("库存并发冲突，请稍后重试");
    }

    /** Bounded store listing with one batched inventory query per page. */
    public ProductStockPage listPage(Long storeId, int page, int size) {
        requireStore(storeId);
        if (page < 0 || page > 1000 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效");
        int offset = page * size;
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStoreId, storeId)
                .orderByDesc(Product::getUpdatedAt, Product::getId)
                .last("LIMIT " + offset + ", " + (size + 1)));
        boolean hasMore = products.size() > size;
        if (hasMore) products = products.subList(0, size);
        if (products.isEmpty()) return new ProductStockPage(List.of(), page, size, false);
        Map<Long, ProductInventory> inventoryBySku = inventoryMapper.selectBatchIds(
                        products.stream().map(Product::getId).toList())
                .stream().collect(Collectors.toMap(ProductInventory::getSkuId, Function.identity()));
        if (inventoryBySku.size() != products.size()) throw new IllegalStateException("商品库存记录不存在");
        List<ProductStockView> items = products.stream()
                .map(product -> new ProductStockView(product, inventoryBySku.get(product.getId()))).toList();
        return new ProductStockPage(items, page, size, hasMore);
    }

    /** 校验店铺有效，避免商品被创建到不存在或停业店铺。 */
    private void requireStore(Long storeId) {
        if (storeId == null || storeId <= 0) throw new IllegalArgumentException("店铺 ID 必须为正整数");
        Store store = storeMapper.selectById(storeId);
        if (store == null || !"OPEN".equals(store.getStatus())) throw new IllegalArgumentException("店铺不存在或未营业");
    }

    /** 读取商品并校验请求店铺归属，阻止跨店编辑。 */
    private Product requireOwnedProduct(Long storeId, Long skuId) {
        requireStore(storeId);
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getId, skuId).eq(Product::getStoreId, storeId));
        if (product == null) throw new IllegalArgumentException("店铺商品不存在");
        return product;
    }

    /** 校验商品新增和编辑共享的名称、价格与库存参数。 */
    private void validateProduct(ProductRequest request) {
        validateProductDetails(request);
        if (request.availableQuantity() == null || request.availableQuantity() < 0) throw new IllegalArgumentException("可用库存不能为负数");
    }

    /** 校验商品新增和编辑共同使用的 SKU、名称及价格字段。 */
    private void validateProductDetails(ProductRequest request) {
        if (request == null || request.skuId() == null || request.skuId() <= 0) throw new IllegalArgumentException("商品 SKU 必须为正整数");
        validateProductDetails(request.name(), request.price());
    }

    /** 校验商品名称和价格字段。 */
    private void validateProductDetails(ProductUpdateRequest request) {
        if (request == null) throw new IllegalArgumentException("商品资料不能为空");
        validateProductDetails(request.name(), request.price());
    }

    private void validateProductDetails(String name, java.math.BigDecimal price) {
        if (name == null || name.isBlank() || name.trim().length() > 200) throw new IllegalArgumentException("商品名称不能为空且不能超过 200 个字符");
        if (price == null || price.signum() < 0 || price.scale() > 2) throw new IllegalArgumentException("商品价格必须为非负两位小数");
    }

    private void recordIndexEvent(Long skuId) {
        if (outbox != null) outbox.record("PRODUCT_INDEX", "product.index", java.util.Map.of("skuId", skuId));
    }

    /** 商品资料及创建时的初始可用库存。 */
    public record ProductRequest(Long skuId, String name, String description, java.math.BigDecimal price,
                                 Integer availableQuantity) {}

    /** 商品编辑资料，不包含创建时才需要的 SKU 和初始库存。 */
    public record ProductUpdateRequest(String name, String description, java.math.BigDecimal price) {}

    public record ProductStockView(Product product, ProductInventory inventory) {}
    public record ProductStockPage(List<ProductStockView> items, int page, int size, boolean hasMore) {}
}
