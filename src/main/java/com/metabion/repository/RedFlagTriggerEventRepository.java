package com.metabion.repository;

import com.metabion.domain.RedFlagTriggerEvent;
import org.springframework.data.repository.Repository;

public interface RedFlagTriggerEventRepository extends Repository<RedFlagTriggerEvent, Long> {

    RedFlagTriggerEvent saveAndFlush(RedFlagTriggerEvent event);
}
