package com.xinghe.trade.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghe.trade.inventory.ProductInventory;
import com.xinghe.trade.inventory.ProductInventoryMapper;
import com.xinghe.trade.store.Store;
import com.xinghe.trade.store.StoreMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 管理店铺商品资料和商品对应的数据库库存。 */
@Service
public class ProductManagementService {
    private final ProductMapper productMapper;
    private final StoreMapper storeMapper;
    private final ProductInventoryMapper inventoryMapper;

    /** 注入商品、店铺与库存 mapper，确保管理操作遵循现有数据库模型。 */
    public ProductManagementService(ProductMapper productMapper, StoreMapper storeMapper,
                                    ProductInventoryMapper inventoryMapper) {
        this.productMapper = productMapper;
        this.storeMapper = storeMapper;
        this.inventoryMapper = inventoryMapper;
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
        return product;
    }

    /** 修改所属店铺商品资料，不允许通过编辑请求迁移商品店铺或覆盖库存。 */
    @Transactional
    public Product update(Long storeId, Long skuId, ProductRequest request) {
        Product product = requireOwnedProduct(storeId, skuId);
        validateProductDetails(request);
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setUpdatedAt(LocalDateTime.now());
        if (productMapper.updateById(product) != 1) throw new IllegalStateException("商品更新失败");
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
        if (request.name() == null || request.name().isBlank() || request.name().trim().length() > 200) throw new IllegalArgumentException("商品名称不能为空且不能超过 200 个字符");
        if (request.price() == null || request.price().signum() < 0 || request.price().scale() > 2) throw new IllegalArgumentException("商品价格必须为非负两位小数");
    }

    /** 商品资料及创建时的初始可用库存。 */
    public record ProductRequest(Long skuId, String name, String description, java.math.BigDecimal price,
                                 Integer availableQuantity) {}
}