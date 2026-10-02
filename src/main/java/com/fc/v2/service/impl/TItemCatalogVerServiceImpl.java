package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.model.auto.TItemCatalogVer;
import com.fc.v2.service.ITItemCatalogVerService;

/**
 * 事项目录条目版次单 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemCatalogVerServiceImpl implements ITItemCatalogVerService {

    private static final int MAX_STAGE = 3;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TItemCatalogVerMapper itemCatalogVerMapper;

    @Override
    public TItemCatalogVer selectTItemCatalogVerById(Long id) {
        return this.itemCatalogVerMapper.selectById(id);
    }

    @Override
    public List<TItemCatalogVer> selectTItemCatalogVerList(QueryWrapper<TItemCatalogVer> queryWrapper) {
        return this.itemCatalogVerMapper.selectList(queryWrapper);
    }

    @Override
    public TItemCatalogVer advance(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st >= MAX_STAGE || (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL)) {
            // 已到顶或已作废，不再走
            return null;
        }
        // 一次只走一档，与实施清单配置卡冻结的步调对齐，名录账才合得上
        r.setStage(st + 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    @Override
    public TItemCatalogVer rollback(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st <= 0 || (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL)) {
            // 已到底或已作废，不再退
            return null;
        }
        // 一次只退一档，不一竿子打回草拟
        r.setStage(st - 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return false;
        }
        r.setContent(remark);
        return this.itemCatalogVerMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r == null) {
            return false;
        }
        return this.itemCatalogVerMapper.deleteById(id) > 0;
    }

}
