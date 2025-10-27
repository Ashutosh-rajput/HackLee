package com.trainingmug.practiceplatform.user.service.impl.learningunit;

import com.trainingmug.practiceplatform.admin.entity.learningunit.CodeCaseEntity;
import com.trainingmug.practiceplatform.admin.entity.learningunit.CodingExerciseEntity;
import com.trainingmug.practiceplatform.admin.entity.learningunit.LanguageEntity;
import com.trainingmug.practiceplatform.admin.exception.learningunit.CodingExerciseNotFoundException;
import com.trainingmug.practiceplatform.admin.exception.learningunit.CourseNotFoundException;
import com.trainingmug.practiceplatform.admin.exception.learningunit.LanguageNotFoundException;
import com.trainingmug.practiceplatform.admin.exception.learningunit.quiz.MaxAttemptsExceededException;
import com.trainingmug.practiceplatform.admin.repository.learningunit.CodingExerciseRepository;
import com.trainingmug.practiceplatform.admin.repository.learningunit.LanguageRepository;
import com.trainingmug.practiceplatform.enums.ExecutionType;
import com.trainingmug.practiceplatform.enums.LearningProgressStatus;
import com.trainingmug.practiceplatform.enums.LearningUnitStatus;
import com.trainingmug.practiceplatform.enums.LearningUnitType;
import com.trainingmug.practiceplatform.user.dto.learningunit.codingexercise.*;
import com.trainingmug.practiceplatform.user.entity.attempt.codingexercise.CodingExerciseAttemptEntity;
import com.trainingmug.practiceplatform.user.entity.attempt.codingexercise.CodingExerciseAttemptHistoryEntity;
import com.trainingmug.practiceplatform.user.entity.enroll.CourseEnrollEntity;
import com.trainingmug.practiceplatform.user.entity.user.AppUserEntity;
import com.trainingmug.practiceplatform.user.exception.AttemptNotFoundException;
import com.trainingmug.practiceplatform.user.exception.user.UserNotFoundException;
import com.trainingmug.practiceplatform.user.model.attempt.Code;
import com.trainingmug.practiceplatform.user.repository.AppUserRepository;
import com.trainingmug.practiceplatform.user.repository.attempt.codingexercise.CodingExerciseAttemptHistoryRepository;
import com.trainingmug.practiceplatform.user.repository.attempt.codingexercise.CodingExerciseAttemptRepository;
import com.trainingmug.practiceplatform.user.repository.enroll.CourseEnrollRepository;
import com.trainingmug.practiceplatform.user.service.dashboard.CourseStatisticsService;
import com.trainingmug.practiceplatform.user.service.dashboard.DailyStreakService;
import com.trainingmug.practiceplatform.user.service.impl.appuser.AppUserServiceImpl;
import com.trainingmug.practiceplatform.user.service.learningunit.AppUserCodingExerciseService;
import com.trainingmug.practiceplatform.user.util.coderunner.CodeRunService;
import com.trainingmug.practiceplatform.user.util.modelmapper.AppUserLearningUnitModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class AppUserCodingExerciseServiceImpl implements AppUserCodingExerciseService {
    private final CodingExerciseRepository codingExerciseRepository;
    private final AppUserLearningUnitModelMapper learningUnitModelMapper;
    private final AppUserServiceImpl appUserService;
    private final AppUserRepository appUserRepository;
    private final LanguageRepository languageRepository;
    private final CodingExerciseAttemptHistoryRepository codingExerciseAttemptHistoryRepository;
    private final CodingExerciseAttemptRepository codingExerciseAttemptRepository;
    private final AppUserLearningUnitModelMapper appUserLearningUnitModelMapper;
    private final CourseEnrollRepository courseEnrollRepository;
    private final DailyStreakService dailyStreakService;
    private final CourseStatisticsService courseStatisticsService;

    @Override
    public CodingExerciseAppUserDTO getCodingExerciseById(Long id) throws CodingExerciseNotFoundException {
        CodingExerciseEntity codingExerciseEntity = codingExerciseRepository.findById(id)
                .orElseThrow(() -> new CodingExerciseNotFoundException("Coding exercise not found with id: " + id));
        log.info("{} - Coding Exercise found: {}", this.getClass().getSimpleName(), codingExerciseEntity.getId());
        return learningUnitModelMapper.toDTOAppUser(codingExerciseEntity);

    }

    @Override
    public Page<CodingExerciseAppUserDTO> getAllCodingExercise(Long courseId, String sortBy, Sort.Direction sortDirection, Integer pageSize, Integer pageIndex) throws CourseNotFoundException {
        Page<CodingExerciseEntity> codingExerciseEntities;
        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(sortDirection, sortBy));

        AppUserEntity appUserEntity = appUserService.getAuthenticatedAppUser();

        // Get all enrolled courses
        List<CourseEnrollEntity> courseEnrollEntities = courseEnrollRepository.findByUserAndProgressStatusIn(
                appUserEntity, List.of(LearningProgressStatus.IN_PROGRESS, LearningProgressStatus.NOT_STARTED)
        );
        boolean isEnrolled = courseId != null && courseEnrollEntities.stream()
                .anyMatch(courseEnrollEntity -> courseEnrollEntity.getCourse().getId().equals(courseId));

        log.info("IsEnrolled: {}", isEnrolled);
        if (courseId == null || !isEnrolled) {
            throw new CourseNotFoundException("Course not found with id: " + courseId);
        }

        codingExerciseEntities = codingExerciseRepository.findAllByCourse_IdAndLearningUnitStatusIn(
                courseId,
                List.of(LearningUnitStatus.ACTIVE, LearningUnitStatus.PENDING_UPDATION_APPROVAL),
                pageable);

        log.info("Coding Exercise found count: {}", codingExerciseEntities.getTotalElements());

        // Fetch attempted codingExercise IDs for the user
        List<Long> attemptedCodingExerciseIds = codingExerciseAttemptHistoryRepository.findAllByAttemptedBy_Id(appUserEntity.getId()).stream()
                .map(history -> history.getCodingExercise().getId())
                .toList();
        log.info("attemptedCodingExerciseIds count: {}", attemptedCodingExerciseIds.size());

        // Filter out unattempted codingExercise
        List<CodingExerciseEntity> unattemptedCodingExercise = codingExerciseEntities.stream()
                .filter(codingExercise -> !attemptedCodingExerciseIds.contains(codingExercise.getId()))
                .toList();
        log.info("unattemptedCodingExercise count: {}", unattemptedCodingExercise.size());

        // Ensure pagination metadata is retained
        Page<CodingExerciseEntity> unattemptedCodingExercisePage = new PageImpl<>(unattemptedCodingExercise, pageable, codingExerciseEntities.getTotalElements());

        return unattemptedCodingExercisePage.map(learningUnitModelMapper::toDTOAppUser);


    }

    @Override
    public CodeExecutionStatus executeUserCode(Long codingExerciseId, Code code) throws Exception {
        AppUserEntity appUserEntity = appUserService.getAuthenticatedAppUser();
        CodingExerciseAttemptEntity savedCodingExerciseAttemptEntity;
        CodingExerciseEntity codingExerciseEntity = codingExerciseRepository.findById(codingExerciseId).orElseThrow(
                () -> new CodingExerciseNotFoundException("Coding exercise not found with id: " + codingExerciseId)
        );
        log.info("found Coding exercise : {}", codingExerciseEntity.getName());
        CodingExerciseAttemptHistoryEntity codingExerciseAttemptHistoryEntity = codingExerciseAttemptHistoryRepository.findByCodingExercise_IdAndAttemptedBy_Id(codingExerciseId, appUserEntity.getId()).orElse(null);
        if (codingExerciseAttemptHistoryEntity != null && codingExerciseAttemptHistoryEntity.getAttemptCount() >= codingExerciseEntity.getMaxAttempts()) {
            throw new MaxAttemptsExceededException("Maximum attempts exceeded for quiz : " + codingExerciseId);
        }
        log.info("Maximum attempts not exceeded for coding exercise");
        Boolean isLastAttempt = codingExerciseAttemptHistoryEntity != null && codingExerciseAttemptHistoryEntity.getAttemptCount() + 1 == codingExerciseEntity.getMaxAttempts();

        LanguageEntity language = languageRepository.findById(code.getLanguage()).orElseThrow(() -> new LanguageNotFoundException(code.getLanguage()));
        log.info("language : {}", language.getName());
        long compileTimeStart = System.currentTimeMillis();
        String errMessage = "";

        if (language.getName().equalsIgnoreCase("java")) {
            errMessage = CodeRunService.compileJavaCode(code.getCode());

        } else if(language.getName().equalsIgnoreCase("python")) {
            String result = CodeRunService.runPythonCode(code.getCode(), codingExerciseEntity.getRunCases().get(0).getInput());
            if (result.startsWith("ERROR: ")) {
                errMessage = result.substring(7);
            } else {
                errMessage = null;
            }
        }else if(language.getName().equalsIgnoreCase("cpp")){
            errMessage=CodeRunService.compileCppCode(code.getCode());
        }else {
            errMessage= CodeRunService.compileCCode(code.getCode());
        }
        long TotalCompileTime = System.currentTimeMillis() - compileTimeStart;
        int attemptCount = codingExerciseAttemptHistoryEntity == null ? 1 : codingExerciseAttemptHistoryEntity.getAttemptCount() + 1;
        log.info("Error massage : {}", errMessage);
        if (errMessage != null) {
            if (code.getExecutionType() == ExecutionType.SUBMIT_CASES) {
                if (codingExerciseAttemptHistoryEntity == null) {
                    codingExerciseAttemptHistoryEntity = new CodingExerciseAttemptHistoryEntity(
                            0L,
                            Timestamp.from(Instant.now()),
                            attemptCount,
                            false,
                            codingExerciseEntity.getTotalSubmitCases(),
                            0,
                            code.getDuration(),
                            TotalCompileTime,
                            0.0,
                            codingExerciseEntity,
                            appUserEntity
                    );
                    codingExerciseAttemptHistoryRepository.save(codingExerciseAttemptHistoryEntity);

                } else {
                    codingExerciseAttemptHistoryEntity.setAttemptCount(attemptCount);
                    codingExerciseAttemptHistoryEntity.setAttemptedOn(Timestamp.from(Instant.now()));
                    codingExerciseAttemptHistoryRepository.save(codingExerciseAttemptHistoryEntity);
                }

                CodingExerciseAttemptEntity codingExerciseAttemptEntity = new CodingExerciseAttemptEntity(
                        0L,
                        Timestamp.from(Instant.now()),
                        false,
                        codingExerciseEntity.getTotalSubmitCases(),
                        0,
                        code.getDuration(),
                        TotalCompileTime,
                        errMessage,
                        code.getCode(),
                        appUserEntity,
                        null,
                        codingExerciseEntity,
                        language,
                        codingExerciseEntity.getMarks(),
                        0,
                        0.0
                );
                savedCodingExerciseAttemptEntity = codingExerciseAttemptRepository.save(codingExerciseAttemptEntity);

                //Update daily Streak**
                dailyStreakService.updateCodingExerciseStreak(appUserEntity.getId(), savedCodingExerciseAttemptEntity, code.getDuration());


            }
            CodeRunService.cleanupGeneratedFiles();
            return new CodeExecutionStatus(
                    0,
                    0,
                    TotalCompileTime,
                    false,
                    codingExerciseEntity.getMaxAttempts() - attemptCount,
                    codingExerciseEntity.getMaxAttempts(),
                    errMessage,
                    null
            );
        }
        log.info("No error");
        List<CodeCaseReportDTO> codeCaseReportDTOS = new ArrayList<>();
        if (code.getExecutionType() == ExecutionType.TEST_CASES) {
            int casePassed = 0;
            long runTimeStart = System.currentTimeMillis();
            for (CodeCaseEntity codeCaseEntity : codingExerciseEntity.getRunCases()) {
                String userCodeOutput = "";
                if (language.getName().equalsIgnoreCase("java")) {
                    userCodeOutput = CodeRunService.runJavaClass("Solution", codeCaseEntity.getInput());
                } else if(language.getName().equalsIgnoreCase("python")) {
                    userCodeOutput = CodeRunService.runPythonCode(code.getCode(), codeCaseEntity.getInput());
                } else if (language.getName().equalsIgnoreCase("cpp")) {
                    userCodeOutput=CodeRunService.runCppCode(codeCaseEntity.getInput());

                } else {
                    userCodeOutput= CodeRunService.runCCode(codeCaseEntity.getInput());
                }
                userCodeOutput = userCodeOutput.trim();
                if (userCodeOutput.equals(codeCaseEntity.getOutput())) {
                    casePassed++;
                }
                log.info("userCodeOutput : {} and expected output {} ", userCodeOutput, codeCaseEntity.getOutput());

                CodeCaseReportDTO report = new CodeCaseReportDTO(
                        codeCaseEntity.getId(),
                        codeCaseEntity.getInput(),
                        codeCaseEntity.getOutput(),
                        userCodeOutput,
                        userCodeOutput.equals(codeCaseEntity.getOutput())
                );
                codeCaseReportDTOS.add(report);
            }
            CodeRunService.cleanupGeneratedFiles();
            long totalRunTime = System.currentTimeMillis() - runTimeStart;
            boolean isPassed = (casePassed * 100 / codingExerciseEntity.getRunCases().size()) >= codingExerciseEntity.getMinimumPercentageToPass();
            log.info("Total run time : {} ms and isPassed {}", totalRunTime, isPassed);
            return new CodeExecutionStatus(
                    codingExerciseEntity.getTotalRunCases(),
                    casePassed,
                    TotalCompileTime + totalRunTime,
                    isPassed,
                    codingExerciseEntity.getMaxAttempts(),
                    codingExerciseEntity.getMaxAttempts() - attemptCount,
                    null,
                    codeCaseReportDTOS
            );
        } else if (code.getExecutionType() == ExecutionType.SUBMIT_CASES) {
            int casePassed = 0;
            long runTimeStart = System.currentTimeMillis();
            for (CodeCaseEntity codeCaseEntity : codingExerciseEntity.getSubmitCases()) {
                String userCodeOutput = "";
                if (language.getName().equalsIgnoreCase("java")) {
                    userCodeOutput = CodeRunService.runJavaClass("Solution", codeCaseEntity.getInput());
                } else if(language.getName().equalsIgnoreCase("python")) {
                    userCodeOutput = CodeRunService.runPythonCode(code.getCode(), codeCaseEntity.getInput());
                } else if (language.getName().equalsIgnoreCase("cpp")) {
                    userCodeOutput = CodeRunService.runCppCode(codeCaseEntity.getInput());

                } else {
                    userCodeOutput = CodeRunService.runCCode(codeCaseEntity.getInput());
                }
                userCodeOutput = userCodeOutput.trim();
                if (userCodeOutput.equals(codeCaseEntity.getOutput())) {
                    casePassed++;
                }
                log.info("userCodeOutput : {} and expected output {} ", userCodeOutput, codeCaseEntity.getOutput());

                CodeCaseReportDTO report = new CodeCaseReportDTO(
                        codeCaseEntity.getId(),
                        codeCaseEntity.getInput(),
                        codeCaseEntity.getOutput(),
                        userCodeOutput,
                        userCodeOutput.equals(codeCaseEntity.getOutput())
                );
                codeCaseReportDTOS.add(report);
            }
            CodeRunService.cleanupGeneratedFiles();
            long totalRunTime = System.currentTimeMillis() - runTimeStart;
            boolean isPassed = (casePassed * 100 / codingExerciseEntity.getSubmitCases().size()) >= codingExerciseEntity.getMinimumPercentageToPass();
            log.info("Total run time : {} ms and isPassed {}", totalRunTime, isPassed);

            int score = (codingExerciseEntity.getMarks() * casePassed) / codingExerciseEntity.getTotalSubmitCases();
            log.info("score : {}, Marks : {}, TotalSubmitCases : {}, CasePassed : {}", score, codingExerciseEntity.getMarks(), codingExerciseEntity.getTotalSubmitCases(), casePassed);
            double percentageScore = codingExerciseEntity.getMarks() > 0 ? (100.0 * score) / codingExerciseEntity.getMarks() : 0.0;
            // **Update the course statistics if the user passed the codingExercise
            if (isPassed && (codingExerciseAttemptHistoryEntity == null || !codingExerciseAttemptHistoryEntity.getIsPassed())) {
                Long courseId = null;
                if (codingExerciseEntity.getCourse() != null)
                    courseId = codingExerciseEntity.getCourse().getId();
                else if (codingExerciseEntity.getChapter() != null)
                    courseId = codingExerciseEntity.getChapter().getCourse().getId();

                log.info("Updating course statistics for first time passing the coding exercise");
                courseStatisticsService.updatePassedCodingExercise(appUserEntity.getId(), courseId);

            }
            double rankScore = calculateRankScore(percentageScore, 7200, code.getDuration(), codingExerciseEntity.getMaxAttempts(), attemptCount);
            if (codingExerciseAttemptHistoryEntity == null) {
                codingExerciseAttemptHistoryEntity = new CodingExerciseAttemptHistoryEntity(
                        0L,
                        Timestamp.from(Instant.now()),
                        attemptCount,
                        isPassed,
                        codingExerciseEntity.getTotalSubmitCases(),
                        casePassed,
                        code.getDuration(),
                        TotalCompileTime + totalRunTime,
                        rankScore,
                        codingExerciseEntity,
                        appUserEntity
                );
                codingExerciseAttemptHistoryRepository.save(codingExerciseAttemptHistoryEntity);
            } else {
                log.info("Coding exercise attempt history is not null");
                codingExerciseAttemptHistoryEntity.setAttemptCount(attemptCount);
                codingExerciseAttemptHistoryEntity.setIsPassed(codingExerciseAttemptHistoryEntity.getIsPassed() || isPassed);
                codingExerciseAttemptHistoryEntity.setPassedCases(Math.max(codingExerciseAttemptHistoryEntity.getPassedCases(), casePassed));
                codingExerciseAttemptHistoryEntity.setMinDuration(Math.min(codingExerciseAttemptHistoryEntity.getMinDuration(), TotalCompileTime + totalRunTime));
                codingExerciseAttemptHistoryEntity.setRankScore(Math.max(codingExerciseAttemptHistoryEntity.getRankScore(), rankScore));
                codingExerciseAttemptHistoryRepository.save(codingExerciseAttemptHistoryEntity);
            }
            CodingExerciseAttemptEntity codingExerciseAttemptEntity = new CodingExerciseAttemptEntity(
                    0L,
                    Timestamp.from(Instant.now()),
                    isPassed,
                    codingExerciseEntity.getTotalSubmitCases(),
                    casePassed,
                    code.getDuration(),
                    TotalCompileTime + totalRunTime,
                    null,
                    code.getCode(),
                    appUserEntity,
                    appUserLearningUnitModelMapper.toEntityList(codeCaseReportDTOS),
                    codingExerciseEntity,
                    language,
                    codingExerciseEntity.getMarks(),
                    score,
                    percentageScore
            );
            savedCodingExerciseAttemptEntity = codingExerciseAttemptRepository.save(codingExerciseAttemptEntity);
            log.info("Saved Coding exercise attempt : {}", savedCodingExerciseAttemptEntity.getId());
            //Update daily Streak
            dailyStreakService.updateCodingExerciseStreak(appUserEntity.getId(), savedCodingExerciseAttemptEntity, code.getDuration());


//            log.info("Total points: " + totalPoints);
            courseStatisticsService.updateOngoingActivity(codingExerciseEntity.getCourse().getId(), LearningUnitType.CODING_EXERCISE, codingExerciseEntity.getName());

            int updatedRows = courseEnrollRepository.updateProgressStatus(appUserEntity.getId(), codingExerciseEntity.getCourse().getId());
            if (updatedRows > 0) {
                log.info("Course progress updated to IN_PROGRESS.");
            } else {
                log.info("No update performed. Course is already IN_PROGRESS or does not exist.");
            }
            log.info("Competed");
            return new CodeExecutionStatus(
                    codingExerciseEntity.getTotalSubmitCases(),
                    casePassed,
                    TotalCompileTime + totalRunTime,
                    isPassed,
                    codingExerciseEntity.getMaxAttempts(),
                    codingExerciseEntity.getMaxAttempts() - attemptCount,
                    null,
                    codeCaseReportDTOS
            );
        }

        return null;
    }

    private double calculateRankScore(double percentageScore, long totalDuration, long totalTimeTaken, Integer maxAttempts, Integer attempt) {
        double normScore = percentageScore / 100.0;
        double normAttempt = ((double) (maxAttempts - attempt)) / (maxAttempts - 1);
        double minTime = (totalDuration * 25.0) / 100.0;
        double normTime = ((double) (totalDuration - totalTimeTaken)) / (totalDuration - minTime);
        // Weightage factors for score, attempts, time.
        double scoreWeightage = 50.0;
        double timeWeightage = 30.0;
        double attemptWeightage = 20.0;
        double finalRankScore = (scoreWeightage * normScore) + (timeWeightage * normTime) + (attemptWeightage * normAttempt);
        return finalRankScore;
    }

    @Override
    public List<CodingExerciseAttemptDTO> getAttempts(Long codingExerciseId, Long userId, String sortBy, Sort.Direction sortDirection) throws UserNotFoundException, CodingExerciseNotFoundException {
        if (!appUserRepository.existsById(userId)) {
            throw new UserNotFoundException("App User not found with this id : " + userId);
        }
        if (!codingExerciseRepository.existsById(codingExerciseId)) {
            throw new CodingExerciseNotFoundException("Coding exercise not found with this id : " + codingExerciseId);
        }
        List<CodingExerciseAttemptEntity> codingExerciseAttempts = codingExerciseAttemptRepository.findByCodingExercise_IdAndAttemptedBy_Id(codingExerciseId, userId);

        return appUserLearningUnitModelMapper.toCodingExerciseAttemptDtoList(codingExerciseAttempts);
    }

    @Override
    public List<CodingExerciseAttemptDTO> getAttempts(Long codingExerciseId, String sortBy, Sort.Direction sortDirection) throws CodingExerciseNotFoundException {
        AppUserEntity appUserEntity = appUserService.getAuthenticatedAppUser();
        if (!codingExerciseRepository.existsById(codingExerciseId)) {
            throw new CodingExerciseNotFoundException("Coding exercise not found with this id : " + codingExerciseId);
        }
        List<CodingExerciseAttemptEntity> codingExerciseAttempts = codingExerciseAttemptRepository.findByCodingExercise_IdAndAttemptedBy_Id(codingExerciseId, appUserEntity.getId());

        return appUserLearningUnitModelMapper.toCodingExerciseAttemptDtoList(codingExerciseAttempts);
    }

    @Override
    public CodingExerciseAttemptDTO getAttempt(Long attemptId) {
        CodingExerciseAttemptEntity codingExerciseAttempt = codingExerciseAttemptRepository.findById(attemptId).orElseThrow(
                () -> new AttemptNotFoundException("Attempt not found with this id : " + attemptId)
        );
        return appUserLearningUnitModelMapper.toCodingExerciseAttemptDto(codingExerciseAttempt);
    }

    @Override
    public CodingExerciseAttemptHistoryDTO getAttemptHistoryByCodingExerciseID(Long codingExerciseId) throws CodingExerciseNotFoundException {
        AppUserEntity appUserEntity = appUserService.getAuthenticatedAppUser();
        if (!codingExerciseRepository.existsById(codingExerciseId))
            throw new CodingExerciseNotFoundException("Coding exercise not found with id: " + codingExerciseId);

        CodingExerciseAttemptHistoryEntity codingExerciseAttemptHistoryEntity = codingExerciseAttemptHistoryRepository.findByCodingExercise_IdAndAttemptedBy_Id(codingExerciseId, appUserEntity.getId()).orElseThrow(
                () -> new AttemptNotFoundException("Attempt history not found with this codingExerciseId : " + codingExerciseId)
        );
        return appUserLearningUnitModelMapper.toCodingExerciseAttemptHistoryDto(codingExerciseAttemptHistoryEntity);
    }

    @Override
    public List<CodingExerciseAttemptHistoryDTO> getAllAttempted(Long chapterId, Long courseId, String sortBy, Sort.Direction sortDirection) {
        List<CodingExerciseEntity> codingExerciseEntities = null;
        AppUserEntity appUserEntity = appUserService.getAuthenticatedAppUser();

        if (chapterId != null && chapterId != 0) {
            codingExerciseEntities = codingExerciseRepository.findAllByChapter_IdAndLearningUnitStatusIn(
                    chapterId,
                    List.of(LearningUnitStatus.ACTIVE, LearningUnitStatus.PENDING_UPDATION_APPROVAL),
                    Sort.by(sortDirection, sortBy)
            );

        } else if (courseId != null && courseId != 0) {
            codingExerciseEntities = codingExerciseRepository.findAllByCourse_IdAndLearningUnitStatusIn(
                    courseId,
                    List.of(LearningUnitStatus.ACTIVE, LearningUnitStatus.PENDING_UPDATION_APPROVAL),
                    Sort.by(sortDirection, sortBy)
            );
        }
        if (codingExerciseEntities == null || codingExerciseEntities.size() == 0) {
            return new ArrayList<>();
        }
        // Remove null values from the list
        return codingExerciseEntities.stream()
                .map(codingExerciseEntity -> codingExerciseAttemptHistoryRepository
                        .findByCodingExercise_IdAndAttemptedBy_Id(codingExerciseEntity.getId(), appUserEntity.getId())
                        .map(appUserLearningUnitModelMapper::toCodingExerciseAttemptHistoryDto)
                        .orElse(null)
                )
                .filter(Objects::nonNull)
                .toList();
    }

/*private Long id;
                private Timestamp attemptedOn;
                private Boolean isPassed;
                private Integer totalCases;
                private Integer passedCases;
                private Long duration;
                private Long avgExecutionTime;
                private String compilationError;
                private String userCode;
                private AppUserEntity attemptedBy;
                private List<CodeCaseReportEntity> codeCaseReports;
                private CodingExerciseEntity codingExercise;
                private LanguageEntity language;
                private Integer totalScore;
                private Integer score;
                private Double percentageScore;
                private DailyStreakEntity dailyStreak;*/
}
