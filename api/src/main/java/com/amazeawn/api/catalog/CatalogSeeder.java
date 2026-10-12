package com.amazeawn.api.catalog;

import com.amazeawn.api.config.AppProperties;
import com.amazeawn.common.messaging.Commands;
import com.amazeawn.common.outbox.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates a deterministic catalog of CATALOG_SIZE products on first start and tells the
 * processor about each one (so it can create inventory rows) through the outbox.
 */
@Component
@Order(2)
public class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

    private static final String[] CATEGORY_NAMES = {
            "Electronics", "Books", "Home & Kitchen", "Sports", "Toys", "Clothing", "Garden", "Beauty",
            "Automotive", "Office", "Pet Supplies", "Music"
    };
    private static final String[] ADJECTIVES = {
            "Compact", "Deluxe", "Essential", "Premium", "Classic", "Portable", "Wireless", "Eco", "Smart", "Ultra"
    };
    private static final String[] NOUNS = {
            "Speaker", "Notebook", "Kettle", "Backpack", "Headphones", "Lamp", "Blender", "Jacket", "Tripod",
            "Charger", "Mug", "Puzzle", "Planter", "Keyboard", "Bottle", "Scarf", "Drill", "Camera"
    };

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final Outbox outbox;
    private final AppProperties props;

    public CatalogSeeder(ProductRepository products, CategoryRepository categories, Outbox outbox, AppProperties props) {
        this.products = products;
        this.categories = categories;
        this.outbox = outbox;
        this.props = props;
    }

    @Override
    @Transactional // seed() is a self-invocation, so the transaction has to start here
    public void run(ApplicationArguments args) {
        if (products.count() > 0) {
            log.info("Catalog already seeded ({} products)", products.count());
            return;
        }
        seed(props.seed().catalogSize(), props.seed().initialStock());
    }

    @Transactional
    public void seed(int size, int initialStock) {
        List<Category> cats = ensureCategories();
        Random random = new Random(42);
        String correlation = "seed-" + UUID.randomUUID();
        List<Product> batch = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            String name = ADJECTIVES[random.nextInt(ADJECTIVES.length)] + " " + NOUNS[random.nextInt(NOUNS.length)] + " " + i;
            BigDecimal price = BigDecimal.valueOf(5 + random.nextInt(495)).add(BigDecimal.valueOf(random.nextInt(100), 2));
            Category category = cats.get(random.nextInt(cats.size()));
            Product product = new Product(String.format("SKU-%06d", i), name,
                    "A " + name.toLowerCase() + " from the " + category.getName() + " range.", price, "USD",
                    category, initialStock);
            batch.add(product);
            outbox.enqueue(new Commands.ProductUpsert(UUID.randomUUID(), correlation, product.getId(), product.getSku(), initialStock));
            if (batch.size() == 500) {
                products.saveAll(batch);
                batch.clear();
            }
        }
        products.saveAll(batch);
        log.info("Seeded {} products across {} categories with stock {}", size, cats.size(), initialStock);
    }

    private List<Category> ensureCategories() {
        List<Category> cats = new ArrayList<>(categories.findAll());
        if (!cats.isEmpty()) {
            return cats;
        }
        int count = Math.min(Math.max(props.seed().categories(), 1), CATEGORY_NAMES.length);
        for (int i = 0; i < count; i++) {
            String name = CATEGORY_NAMES[i];
            cats.add(categories.save(new Category(name, name.toLowerCase().replaceAll("[^a-z0-9]+", "-"))));
        }
        return cats;
    }
}
