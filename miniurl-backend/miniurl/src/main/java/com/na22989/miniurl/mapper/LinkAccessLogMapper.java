package com.na22989.miniurl.mapper;
import java.time.LocalDateTime;
import java.util.Date;

import com.na22989.miniurl.model.dto.link.HourlyStatus;
import org.apache.ibatis.annotations.Param;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.na22989.miniurl.model.entity.LinkAccessLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LinkAccessLogMapper extends BaseMapper<LinkAccessLog> {


    Long countDistinctIpByLinkId(@Param("linkId")Long linkId);

    Long countDistinctIpByLinkIdAndAccessTimeBetween(@Param("linkId")Long linkId, @Param("minAccessTime") LocalDateTime minAccessTime, @Param("maxAccessTime")LocalDateTime maxAccessTime);

/**
 * 根据链接ID和访问时间范围统计每小时的趋势数据
 *
 * @param linkId 链接ID，用于指定要统计的特定链接
 * @param minAccessTime 访问时间的起始范围，包含该时间点
 * @param maxAccessTime 访问时间的结束范围，包含该时间点
 * @return 返回一个包含每小时状态信息的列表，每个元素代表一个小时的统计数据
 */
    List<HourlyStatus> countHourlyTrendByLinkIdAndAccessTimeBetween(@Param("linkId")Long linkId, @Param("minAccessTime") LocalDateTime minAccessTime, @Param("maxAccessTime")LocalDateTime maxAccessTime);









}