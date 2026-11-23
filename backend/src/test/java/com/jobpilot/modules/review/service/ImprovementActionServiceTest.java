package com.jobpilot.modules.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.review.dto.CreateImprovementActionRequest;
import com.jobpilot.modules.review.dto.ImprovementActionDraftResponse;
import com.jobpilot.modules.review.dto.UpdateImprovementActionRequest;
import com.jobpilot.modules.review.entity.ImprovementAction;
import com.jobpilot.modules.review.entity.InterviewEvent;
import com.jobpilot.modules.review.entity.ReviewSession;
import com.jobpilot.modules.review.mapper.ImprovementActionMapper;
import com.jobpilot.modules.review.mapper.InterviewEventMapper;
import com.jobpilot.modules.review.mapper.ReviewSessionMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 改进动作：来源校验、状态流转、删除与确认同步（不删用户已有行动）。 */
@ExtendWith(MockitoExtension.class)
class ImprovementActionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ImprovementActionMapper actionMapper;
    @Mock
    private ReviewSessionMapper reviewSessionMapper;
    @Mock
    private InterviewEventMapper eventMapper;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(ImprovementAction.class, ReviewSession.class, InterviewEvent.class);
    }

    private ImprovementActionService service() {
        return new ImprovementActionService(actionMapper, reviewSessionMapper, eventMapper);
    }

    private static ImprovementAction action(long id, String text, String category, String status) {
        ImprovementAction action = new ImprovementAction();
        action.setId(id);
        action.setAction(text);
        action.setCategory(category);
        action.setStatus(status);
        action.setCreatedAt(OffsetDateTime.now());
        action.setUpdatedAt(OffsetDateTime.now());
        return action;
    }

    private static InterviewEvent event(long id, long applicationId) {
        InterviewEvent event = new InterviewEvent();
        event.setId(id);
        event.setApplicationId(applicationId);
        return event;
    }

    @Test
    void pageMapsStatusFilter() {
        Page<ImprovementAction> page = new Page<>(1, 20);
        page.setRecords(List.of(action(1L, "复习 GC", "知识点补强", "TODO")));
        page.setTotal(1);
        when(actionMapper.selectPage(any(), any())).thenReturn(page);

        var result = service().page(1, 20, "TODO", 30L);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().get(0).action()).isEqualTo("复习 GC");
        assertThat(result.items().get(0).status()).isEqualTo("TODO");
    }

    @Test
    void createDerivesApplicationFromReviewOrEvent() {
        ReviewSession review = new ReviewSession();
        review.setId(30L);
        review.setEventId(20L);
        when(reviewSessionMapper.selectById(30L)).thenReturn(review);
        when(eventMapper.selectById(20L)).thenReturn(event(20L, 7L));
        when(actionMapper.insert(any(ImprovementAction.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ImprovementAction.class).setId(1L);
            return 1;
        });

        var response = service().create(new CreateImprovementActionRequest("  复习 GC  ", " 知识点补强 ",
                null, 30L, null));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.applicationId()).isEqualTo(7L);
        assertThat(response.action()).isEqualTo("复习 GC");
        assertThat(response.category()).isEqualTo("知识点补强");
        assertThat(response.status()).isEqualTo("TODO");

        when(reviewSessionMapper.selectById(31L)).thenReturn(null);
        assertCode(() -> service().create(new CreateImprovementActionRequest("行动", null, null, 31L, null)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void updateAppliesFieldsAndValidates() {
        when(actionMapper.selectById(1L)).thenReturn(action(1L, "旧行动", "旧分类", "TODO"));
        when(eventMapper.selectById(20L)).thenReturn(event(20L, 7L));
        when(actionMapper.updateById(any(ImprovementAction.class))).thenReturn(1);

        var response = service().update(1L, new UpdateImprovementActionRequest(
                MAPPER.getNodeFactory().textNode(" 新行动 "),
                MAPPER.getNodeFactory().textNode(" 表达结构 "),
                MAPPER.getNodeFactory().textNode("VERIFIED"),
                MAPPER.getNodeFactory().textNode("2026-12-01T09:00:00+08:00"),
                MAPPER.getNodeFactory().numberNode(20L),
                MAPPER.getNodeFactory().textNode(" 已在下一场验证 ")));

        assertThat(response.action()).isEqualTo("新行动");
        assertThat(response.category()).isEqualTo("表达结构");
        assertThat(response.status()).isEqualTo("VERIFIED");
        assertThat(response.verifiedEventId()).isEqualTo(20L);
        assertThat(response.evidence()).isEqualTo("已在下一场验证");

        assertCode(() -> service().update(1L, new UpdateImprovementActionRequest(
                null, null, MAPPER.getNodeFactory().textNode("UNKNOWN"), null, null, null)),
                ErrorCode.BAD_REQUEST);
        assertCode(() -> service().update(1L, new UpdateImprovementActionRequest(
                com.fasterxml.jackson.databind.node.NullNode.getInstance(), null, null, null, null, null)),
                ErrorCode.BAD_REQUEST);
        assertCode(() -> service().update(1L, new UpdateImprovementActionRequest(
                null, null, null, null, MAPPER.getNodeFactory().numberNode(99L), null)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void deleteRequiresExistingAction() {
        assertCode(() -> service().delete(1L), ErrorCode.NOT_FOUND);

        when(actionMapper.selectById(1L)).thenReturn(action(1L, "行动", null, "TODO"));
        when(actionMapper.deleteById(1L)).thenReturn(1);
        service().delete(1L);
        verify(actionMapper).deleteById(1L);
    }

    @Test
    void syncFromDraftsUpdatesExistingAndAddsNewWithoutDeleting() {
        ImprovementAction existing = action(1L, "复习 GC", "旧分类", "VERIFIED");
        existing.setSourceReviewId(30L);
        when(actionMapper.selectList(any())).thenReturn(List.of(existing));
        when(actionMapper.updateById(any(ImprovementAction.class))).thenReturn(1);
        when(actionMapper.insert(any(ImprovementAction.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ImprovementAction.class).setId(2L);
            return 1;
        });

        service().syncFromDrafts(30L, 20L, 7L, List.of(
                new ImprovementActionDraftResponse("复习 GC", "知识点补强", "理由"),
                new ImprovementActionDraftResponse("练习表达", "表达结构", "理由")));

        assertThat(existing.getCategory()).isEqualTo("知识点补强");
        assertThat(existing.getStatus()).isEqualTo("VERIFIED");
        verify(actionMapper).updateById(existing);
        verify(actionMapper).insert(any(ImprovementAction.class));
        verify(actionMapper, never()).deleteById(any(Long.class));
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
