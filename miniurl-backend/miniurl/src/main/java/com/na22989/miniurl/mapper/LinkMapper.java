package com.na22989.miniurl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.na22989.miniurl.model.entity.Link;
import com.na22989.miniurl.task.SyncClickCounts2DBTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LinkMapper extends BaseMapper<Link> {

    /**
     * 批量累加点击计数（click_count = click_count + 增量）
     *
     * @param clickSyncDTOS 短码与点击增量列表
     * @return 受影响行数
     */
    int batchUpdateClickCount(@Param("list") List<SyncClickCounts2DBTask.ClickSyncDTO> clickSyncDTOS);
}
