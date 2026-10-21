package com.acme.secrets.sync.source;

import java.util.List;

/**
 * One page of a paginated listing.
 *
 * @param total number of items in the full listing, across all pages
 */
public record Page<T>(List<T> items, int offset, int total) {

    public Page {
        items = List.copyOf(items);
    }
}
