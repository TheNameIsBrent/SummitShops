package com.oneblock.shops.shop;

/**
 * Defines the direction of a shop transaction.
 *
 * <ul>
 *   <li>{@code BUY}  – customers buy items FROM the chest (owner stocks items, customers pay).</li>
 *   <li>{@code SELL} – customers sell items INTO the chest (customers receive payment).</li>
 * </ul>
 */
public enum ShopMode {
    BUY,
    SELL
}
