package com.oneblock.shops.shop;

/**
 * Result codes returned by {@link ShopService} after a transaction attempt.
 */
public enum TransactionResult {
    /** Transaction completed successfully. */
    SUCCESS,

    /** Shop has no item / price configured. */
    NOT_CONFIGURED,

    /** Player is not a member of the shop's island. */
    NOT_ISLAND_MEMBER,

    /** Shop chest has no matching items (BUY mode). */
    OUT_OF_STOCK,

    /** Shop chest is full and cannot accept items (SELL mode). */
    SHOP_FULL,

    /** Buying player doesn't have enough currency. */
    INSUFFICIENT_FUNDS,

    /** Selling player doesn't have space in their inventory. */
    PLAYER_INVENTORY_FULL,

    /** Currency provider is unavailable (e.g., Vault not hooked). */
    CURRENCY_UNAVAILABLE,

    /** The chest block no longer exists at the shop location. */
    CHEST_MISSING,

    /** An unexpected error occurred. */
    ERROR
}
