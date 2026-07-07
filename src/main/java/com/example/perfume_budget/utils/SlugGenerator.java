package com.example.perfume_budget.utils;

import java.util.UUID;

public class SlugGenerator {
    private SlugGenerator(){
        throw new IllegalStateException(("Utility class"));
    }

    public static String generateSlug(String name){
        String slug = generateNormalSlug(name);
        return slug+UUID.randomUUID().toString().substring(0,8);
    }
    public static String generateNormalSlug(String name){
        String slug = name.trim().toLowerCase();
        slug = slug.replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("(^-+|-+$)","");
        return slug;
    }
}
