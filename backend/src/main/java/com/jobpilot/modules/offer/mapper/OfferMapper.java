package com.jobpilot.modules.offer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.modules.offer.entity.Offer;
import org.apache.ibatis.annotations.Mapper;

/** Offer 持久化。 */
@Mapper
public interface OfferMapper extends BaseMapper<Offer> {
}
