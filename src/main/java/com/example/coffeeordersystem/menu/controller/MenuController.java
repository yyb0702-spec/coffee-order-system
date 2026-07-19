package com.example.coffeeordersystem.menu.controller;

import com.example.coffeeordersystem.menu.dto.MenuResponse;
import com.example.coffeeordersystem.menu.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping
    public List<MenuResponse> getMenus() {
        return menuService.getMenus();
    }
}
