package com.example.coffeeordersystem.menu.dto;

import com.example.coffeeordersystem.menu.entity.Menu;

public record MenuResponse(Long id, String name, Integer price) {

    public static MenuResponse from(Menu menu) {
        return new MenuResponse(menu.getId(), menu.getName(), menu.getPrice());
    }
}
