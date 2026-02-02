package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.MdCurrency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MdCurrencyRepository extends JpaRepository<MdCurrency, String> {

    List<MdCurrency> findByIsActiveTrueOrderByCurrencyCodeAsc();

    Optional<MdCurrency> findByCurrencyCode(String currencyCode);

    boolean existsByCurrencyCode(String currencyCode);
}
