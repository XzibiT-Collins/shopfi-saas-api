package com.example.perfume_budget.specification;

import com.example.perfume_budget.model.Product;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

public class ProductSpecification {
    /** The LIKE escape character of every search pattern built here (CAT-05). */
    static final char LIKE_ESCAPE = '\\';

    private ProductSpecification() {
    }

    /**
     * CAT-05: a typed term is literal text. It is trimmed, lower-cased, and {@code \}, {@code %} and {@code _} are escaped
     * so a shopper typing "50%" or "a_b" does not get wildcard matching. {@code null} when there is nothing to search for.
     */
    static String likePattern(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return null;
        }
        String term = searchTerm.strip().toLowerCase();
        StringBuilder out = new StringBuilder(term.length() + 8).append('%');
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                out.append(LIKE_ESCAPE);
            }
            out.append(c);
        }
        return out.append('%').toString();
    }

    public static Specification<Product> filterProducts(Long categoryId, String searchTerm) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Ecommerce visibility requires both active and enlisted
            predicates.add(criteriaBuilder.equal(root.get("isActive"), true));
            predicates.add(criteriaBuilder.equal(root.get("isEnlisted"), true));

            if (categoryId != null && categoryId > 0) {
                predicates.add(criteriaBuilder.equal(root.get("category").get("id"), categoryId));
            }

            String pattern = likePattern(searchTerm);
            if (pattern != null) {
                Predicate nameLike = criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), pattern, LIKE_ESCAPE);
                // public search matches ONLY public fields (name): matching the hidden sku would make SKUs enumerable
                predicates.add(nameLike);
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    public static Specification<Product> filterProductsForAdmin(Long categoryId, String searchTerm) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (categoryId != null && categoryId > 0) {
                predicates.add(criteriaBuilder.equal(root.get("category").get("id"), categoryId));
            }

            String pattern = likePattern(searchTerm);
            if (pattern != null) {
                Predicate nameLike = criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), pattern, LIKE_ESCAPE);
                Predicate skuLike = criteriaBuilder.like(criteriaBuilder.lower(root.get("sku")), pattern, LIKE_ESCAPE);
                predicates.add(criteriaBuilder.or(nameLike, skuLike));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
