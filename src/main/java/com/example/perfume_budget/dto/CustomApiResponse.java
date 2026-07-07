package com.example.perfume_budget.dto;

public record CustomApiResponse<T>(
        String description,
        T data
) {

    public static <T> CustomApiResponse<T> error(String message) {
        return new CustomApiResponse<>( message, null);
    }

    public static <T> CustomApiResponse<T> error(String message, T data) {
        return new CustomApiResponse<>( message, data);
    }

    public static <T> CustomApiResponse<T> success(String message){
        return new CustomApiResponse<>(message,null);
    }

    public static <T> CustomApiResponse<T> success(T data){
        return new CustomApiResponse<>(null,data);
    }

    public static <T> CustomApiResponse<T> success(String message, T data) {
        return new CustomApiResponse<>( message, data);
    }
}