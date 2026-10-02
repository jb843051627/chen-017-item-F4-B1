package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.mapper.auto.TItemDeptArchMapper;
import com.fc.v2.model.auto.TItemDeptArch;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.service.ITItemImplementCardService;
import com.fc.v2.util.StringUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 实施清单配置卡 Controller
 *
 * @author fuce
 * @date 2026-09-12
 */
@Api(value = "实施清单配置卡")
@Controller
@RequestMapping("/ItemImplementCardController")
public class ItemImplementCardController extends BaseController {

    private final String prefix = "admin/itemImplementCard";

    @Autowired
    private ITItemImplementCardService itemImplementCardService;

    @Autowired
    private TItemDeptArchMapper itemDeptArchMapper;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("item:itemImplementCard:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "实施清单配置卡集合查询", action = "list")
    @ApiOperation(value = "分页查询", notes = "分页查询")
    @GetMapping("/list")
    @RequiresPermissions("item:itemImplementCard:list")
    @ResponseBody
    public ResultTable list(TItemImplementCard record) {
        QueryWrapper<TItemImplementCard> queryWrapper = new QueryWrapper<TItemImplementCard>();
        queryWrapper.eq(record.getSiteId() != null, "site_id", record.getSiteId());
        queryWrapper.eq(record.getStatus() != null, "status", record.getStatus());
        queryWrapper.like(StringUtils.isNotEmpty(record.getBillNo()), "bill_no", record.getBillNo());
        startPage();
        com.github.pagehelper.PageInfo<TItemImplementCard> page =
                new com.github.pagehelper.PageInfo<TItemImplementCard>(
                        itemImplementCardService.selectTItemImplementCardList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @ApiOperation(value = "新增跳转", notes = "新增跳转")
    @GetMapping("/add")
    public String add(ModelMap modelMap) {
        modelMap.put("sites", activeSites());
        return prefix + "/add";
    }

    @Log(title = "实施清单配置卡新增", action = "add")
    @ApiOperation(value = "新增", notes = "新增")
    @PostMapping("/add")
    @RequiresPermissions("item:itemImplementCard:add")
    @ResponseBody
    public AjaxResult add(TItemImplementCard record) {
        return AjaxResult.success(200, "建卡成功", itemImplementCardService.insertTItemImplementCard(record));
    }

    @ApiOperation(value = "修改跳转", notes = "修改跳转")
    @GetMapping("/edit/{id}")
    public String edit(@PathVariable("id") Long id, ModelMap mmap) {
        mmap.put("card", itemImplementCardService.selectTItemImplementCardById(id));
        mmap.put("sites", activeSites());
        return prefix + "/edit";
    }

    @Log(title = "实施清单配置卡修改", action = "edit")
    @ApiOperation(value = "修改保存", notes = "修改保存：仅待核对卡可改要素")
    @PostMapping("/edit")
    @RequiresPermissions("item:itemImplementCard:edit")
    @ResponseBody
    public AjaxResult editSave(TItemImplementCard record) {
        return AjaxResult.success(200, "保存成功", itemImplementCardService.updateTItemImplementCard(record));
    }

    @Log(title = "实施清单配置卡推进", action = "advance")
    @ApiOperation(value = "推进一档", notes = "待核对→已核对→已冻住，一次只许一档")
    @PostMapping("/advance")
    @RequiresPermissions("item:itemImplementCard:advance")
    @ResponseBody
    public AjaxResult advance(Long id) {
        TItemImplementCard card = itemImplementCardService.advanceTItemImplementCard(id);
        return AjaxResult.success(200, card.getStatus() == 2 ? "已冻住" : "已核对", card);
    }

    @Log(title = "实施清单配置卡退回", action = "revert")
    @ApiOperation(value = "整档退回", notes = "仅已核对→待核对；留痕保留")
    @PostMapping("/revert")
    @RequiresPermissions("item:itemImplementCard:revert")
    @ResponseBody
    public AjaxResult revert(Long id) {
        return AjaxResult.success(200, "已退回待核对", itemImplementCardService.revertTItemImplementCard(id));
    }

    @Log(title = "实施清单配置卡删除", action = "remove")
    @ApiOperation(value = "删除", notes = "冻住卡拦下，其余逻辑删除")
    @DeleteMapping("/remove")
    @RequiresPermissions("item:itemImplementCard:remove")
    @ResponseBody
    public AjaxResult remove(String ids) {
        int n = itemImplementCardService.deleteTItemImplementCardByIds(ids);
        return AjaxResult.success(200, "撤掉 " + n + " 张卡", n);
    }

    @ApiOperation(value = "核对留痕", notes = "该卡每一次核对/退回/冻住/改档的先后记录")
    @GetMapping("/logs/{id}")
    @ResponseBody
    public AjaxResult logs(@PathVariable("id") Long id) {
        return AjaxResult.success(200, "ok", itemImplementCardService.listCheckLogs(id));
    }

    @ApiOperation(value = "部门名额账", notes = "屏上各档卡数对底册记的已冻名额")
    @GetMapping("/quota/{siteId}")
    @ResponseBody
    public AjaxResult quota(@PathVariable("siteId") Integer siteId) {
        return AjaxResult.success(200, "ok", itemImplementCardService.quotaOfSite(siteId));
    }

    @Log(title = "部门名额账对账", action = "reconcile")
    @ApiOperation(value = "名额账拨正", notes = "按卡实重算底册已冻名额")
    @PostMapping("/reconcile/{siteId}")
    @RequiresPermissions("item:itemImplementCard:reconcile")
    @ResponseBody
    public AjaxResult reconcile(@PathVariable("siteId") Integer siteId) {
        return AjaxResult.success(200, "已拨正", itemImplementCardService.reconcileSite(siteId));
    }

    @ApiOperation(value = "底册下拉", notes = "在编与已撤并底册都列出，已撤并的供筛选用")
    @GetMapping("/sites")
    @ResponseBody
    public AjaxResult sites() {
        return AjaxResult.success(200, "ok",
                itemDeptArchMapper.selectList(new QueryWrapper<TItemDeptArch>()
                        .eq("del_flag", 0).orderByAsc("id")));
    }

    /** 在编底册，供下拉选择（已撤并的不允许新建卡） */
    private java.util.List<TItemDeptArch> activeSites() {
        return itemDeptArchMapper.selectList(new QueryWrapper<TItemDeptArch>()
                .eq("del_flag", 0).eq("status", 0).orderByAsc("id"));
    }
}
