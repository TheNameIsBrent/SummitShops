package com.oneblock.shops.storage;

import com.oneblock.shops.shop.Shop;

import java.util.List;
import java.util.UUID;

/**
 * Abstraction over the persistence backend.
 *
 * <p>Implementations must be thread-safe for the {@code saveAll} and
 * {@code deleteAsync} operations, which may be called off the main thread.</p>
 */
public interface StorageProvider {

    /**
     * Initializes the storage backend (create tables, open files, etc.).
     * Called once during plugin enable.
     */
    void initialize();

    /**
     * Loads all shops from persistent storage.
     * Called synchronously during startup before the server accepts connections.
     *
     * @return mutable list of all stored shops
     */
    List<Shop> loadAll();

    /**
     * Persists a single shop. May be called from any thread.
     */
    void save(Shop shop);

    /**
     * Persists all shops in a batch. May be called from any thread.
     */
    void saveAll(List<Shop> shops);

    /**
     * Deletes a shop by ID asynchronously.
     */
    void deleteAsync(UUID shopId);

    /**
     * Flushes any pending writes and closes connections.
     * Called during plugin disable.
     */
    void shutdown();
}
