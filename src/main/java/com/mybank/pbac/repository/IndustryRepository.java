package com.mybank.pbac.repository;
import com.mybank.pbac.model.Industry; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface IndustryRepository extends JpaRepository<Industry,Long>{Optional<Industry> findByCodeIgnoreCase(String code);List<Industry> findAllByOrderByNameAsc();}
