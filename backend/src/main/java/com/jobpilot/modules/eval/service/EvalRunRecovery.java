package com.jobpilot.modules.eval.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动恢复：进程中断后残留的 PENDING / PROCESSING 运行置 FAILED，
 * 未执行用例置 SKIPPED，避免「永远转圈」和活跃运行唯一索引阻塞新运行。
 */
@Component
public class EvalRunRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunRecovery.class);

    private final LlmEvalRunMapper runMapper;
    private final LlmEvalRunCaseMapper caseMapper;

    public EvalRunRecovery(LlmEvalRunMapper runMapper, LlmEvalRunCaseMapper caseMapper) {
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<LlmEvalRun> active = runMapper.selectList(Wrappers.<LlmEvalRun>lambdaQuery()
                .in(LlmEvalRun::getStatus, List.of("PENDING", "PROCESSING")));
        for (LlmEvalRun run : active) {
            caseMapper.update(null, Wrappers.<LlmEvalRunCase>lambdaUpdate()
                    .eq(LlmEvalRunCase::getRunId, run.getId()).eq(LlmEvalRunCase::getStatus, "PENDING")
                    .set(LlmEvalRunCase::getStatus, "SKIPPED").set(LlmEvalRunCase::getError, "服务重启，未执行")
                    .set(LlmEvalRunCase::getUpdatedAt, OffsetDateTime.now()));
            runMapper.update(null, Wrappers.<LlmEvalRun>lambdaUpdate().eq(LlmEvalRun::getId, run.getId())
                    .set(LlmEvalRun::getStatus, "FAILED").set(LlmEvalRun::getError, "服务重启，评测中断")
                    .set(LlmEvalRun::getFinishedAt, OffsetDateTime.now()));
            log.warn("评测运行 {} 因服务重启标记 FAILED", run.getId());
        }
    }
}
