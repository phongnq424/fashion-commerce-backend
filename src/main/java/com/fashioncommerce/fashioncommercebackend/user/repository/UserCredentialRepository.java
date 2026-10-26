package com.fashioncommerce.fashioncommercebackend.user.repository;

import com.fashioncommerce.fashioncommercebackend.user.entity.UserCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> { }
