package dev.doppelganger.server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperationRepository extends JpaRepository<OperationEntity, Long> {
    List<OperationEntity> findAllByOrderByPositionAsc();
}
