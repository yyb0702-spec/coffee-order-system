package com.example.coffeeordersystem.menu.repository;

import com.example.coffeeordersystem.menu.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, Long> {
}
