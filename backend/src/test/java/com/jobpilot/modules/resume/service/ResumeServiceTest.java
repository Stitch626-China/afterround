package com.jobpilot.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.infrastructure.storage.FileStorage;
import com.jobpilot.modules.application.mapper.JobApplicationMapper;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.mapper.ResumeAnalysisMapper;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import org.junit.jupiter.api.Test;

/** 文件删除失败时不得删除数据库记录，避免产生孤儿文件。 */
class ResumeServiceTest {

    @Test
    void fileDeleteFailureKeepsDatabaseRecord() {
        ResumeMapper resumeMapper = mock(ResumeMapper.class);
        ResumeAnalysisMapper analysisMapper = mock(ResumeAnalysisMapper.class);
        JobApplicationMapper applicationMapper = mock(JobApplicationMapper.class);
        FileStorage storage = mock(FileStorage.class);
        ResumeAnalysisService analysisService = mock(ResumeAnalysisService.class);

        Resume resume = new Resume();
        resume.setId(1L);
        resume.setStorageKey("resumes/a.pdf");
        when(resumeMapper.selectById(1L)).thenReturn(resume);
        when(applicationMapper.selectCount(any())).thenReturn(0L);
        doThrow(new IllegalStateException("disk error")).when(storage).delete("resumes/a.pdf");

        ResumeService service = new ResumeService(resumeMapper, analysisMapper, applicationMapper,
                storage, analysisService);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        verify(resumeMapper, never()).deleteById(any(Long.class));
        verify(analysisMapper, never()).delete(any());
    }
}
