package com.callisto.sdk.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Generic paginated container.
 *
 * @param <T> the item type.
 */
public class Paginated<T> {

    private final List<T> items;
    private final int total;
    private final int perPage;
    private final int currentPage;
    private final Integer next;
    private final Integer previous;
    private final int totalPages;

    public Paginated(List<T> items, int total, int perPage, int currentPage,
                     Integer next, Integer previous, int totalPages) {
        this.items = items;
        this.total = total;
        this.perPage = perPage;
        this.currentPage = currentPage;
        this.next = next;
        this.previous = previous;
        this.totalPages = totalPages;
    }

    /**
     * Builds a {@code Paginated<T>} from a JSON node, mapping each item via {@code itemFactory}.
     */
    public static <T> Paginated<T> fromJson(JsonNode data, ObjectMapper mapper, Class<T> itemType) {
        return fromJson(data, node -> mapper.convertValue(node, itemType));
    }

    public static <T> Paginated<T> fromJson(JsonNode data, Function<JsonNode, T> itemFactory) {
        List<T> items = new ArrayList<>();
        if (data != null) {
            JsonNode itemsNode = data.get("items");
            if (itemsNode != null && itemsNode.isArray()) {
                for (JsonNode itemNode : itemsNode) {
                    items.add(itemFactory.apply(itemNode));
                }
            }
        }
        return new Paginated<>(
                items,
                intField(data, "total"),
                intField(data, "per_page"),
                intField(data, "current_page"),
                nullableIntField(data, "next"),
                nullableIntField(data, "previous"),
                intField(data, "total_pages"));
    }

    private static int intField(JsonNode data, String name) {
        if (data == null) {
            return 0;
        }
        JsonNode node = data.get(name);
        return node != null && node.isNumber() ? node.asInt() : 0;
    }

    private static Integer nullableIntField(JsonNode data, String name) {
        if (data == null) {
            return null;
        }
        JsonNode node = data.get(name);
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    public List<T> getItems() {
        return items;
    }

    public int getTotal() {
        return total;
    }

    public int getPerPage() {
        return perPage;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public Integer getNext() {
        return next;
    }

    public Integer getPrevious() {
        return previous;
    }

    public int getTotalPages() {
        return totalPages;
    }
}
