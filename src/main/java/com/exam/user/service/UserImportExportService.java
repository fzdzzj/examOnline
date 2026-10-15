package com.exam.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.RoleHierarchy;
import com.exam.clazz.entity.ClassEntity;
import com.exam.clazz.entity.UserClass;
import com.exam.clazz.mapper.ClassMapper;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.score.support.ExcelSheetWriter;
import com.exam.user.dto.UserImportResult;
import com.exam.user.entity.Role;
import com.exam.user.entity.User;
import com.exam.user.entity.UserRole;
import com.exam.user.mapper.RoleMapper;
import com.exam.user.mapper.UserMapper;
import com.exam.user.mapper.UserRoleMapper;
import com.exam.user.support.UserImportColumn;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户/学生批量导入导出服务（add-user-import-export）。
 *
 * <ul>
 *   <li><b>导入</b>：逐行容错导入学生，固定角色 STUDENT，初始密码 BCrypt 入库；
 *       文件级上限校验（默认 1000 行）；查重不覆盖（库内与文件内重复均跳过并报错）；
 *       班级名称解析（不存在或歧义跳过不自动建班）；单行独立事务。</li>
 *   <li><b>模板</b>：表头与导入解析、导出严格一致，输出 xlsx 附件。</li>
 *   <li><b>导出</b>：scope=student|teacher|all，可选 classId 过滤学生；SXSSF 流式防 OOM；
 *       未软删口径对齐管理侧列表。</li>
 * </ul>
 */
@Slf4j
@Service
public class UserImportExportService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");

    /**
     * 分批查询批大小（harden-review-flow-and-resource-limits M-06）：单次
     * {@code WHERE id IN (...)} 最多携带 {@value #BATCH_SIZE} 个参数，
     * 防止用户数达数千/上万时构造超长 IN 列表触发 MySQL {@code max_allowed_packet}
     * 报文溢出与大对象瞬时堆积。与 {@link ScoreExportService} 的答卷分批同量级。
     */
    static final int BATCH_SIZE = 500;

    /**
     * Unicode 空白剥离（harden-data-sanitization-and-ui-states M-02）：剥除首尾全部 Unicode 空白。
     * 覆盖半角空格、\u3000 中文全角空格、\u00A0 不换行空格（NBSP）等——
     * Java 原生 {@code String.trim()} 仅处理 ASCII ≤ 0x20，用户从中文文档复制
     * 「　学号　」「　班级名　」时会导致校验与匹配失败整行报错。
     */
    private static String stripWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("^[\\s\\u3000\\u00A0]+|[\\s\\u3000\\u00A0]+$", "");
    }

    private final UserService userService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final ClassMapper classMapper;
    private final UserClassMapper userClassMapper;
    private final TransactionTemplate transactionTemplate;
    private final int maxRows;

    public UserImportExportService(UserService userService,
                                  UserMapper userMapper,
                                  RoleMapper roleMapper,
                                  UserRoleMapper userRoleMapper,
                                  ClassMapper classMapper,
                                  UserClassMapper userClassMapper,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${exam.user-import.max-rows:1000}") int maxRows) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.classMapper = classMapper;
        this.userClassMapper = userClassMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxRows = maxRows;
    }

    // ==================== 导入 ====================

    /**
     * 逐行容错导入学生。
     */
    public UserImportResult importUsers(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "请选择 xlsx 文件");
        }
        if (!"xlsx".equalsIgnoreCase(extensionOf(file.getOriginalFilename()))) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "仅支持 .xlsx 格式的文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "文件读取失败");
        }

        Sheet sheet = parseSheet(bytes);
        verifyHeader(sheet);
        int dataRowCount = countDataRows(sheet);
        if (dataRowCount > maxRows) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "数据行数 " + dataRowCount + " 超过单次导入上限 " + maxRows + "，请分批导入");
        }

        UserImportResult result = new UserImportResult();
        DataFormatter formatter = new DataFormatter();
        Set<String> seenUsernamesInFile = new HashSet<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row excelRow = sheet.getRow(i);
            if (excelRow == null || isEmptyRow(excelRow, formatter)) {
                continue;
            }
            int excelLine = i + 1;
            List<String> rowErrors = new ArrayList<>();

            String username = cellText(excelRow, UserImportColumn.USERNAME, formatter).trim();
            if (!StringUtils.hasText(username)) {
                rowErrors.add("学号不能为空");
            } else {
                if (!seenUsernamesInFile.add(username)) {
                    rowErrors.add("学号重复（文件内重复）");
                } else if (userService.existsByUsername(username)) {
                    rowErrors.add("学号已存在");
                }
            }

            String name = cellText(excelRow, UserImportColumn.NAME, formatter).trim();
            if (!StringUtils.hasText(name)) {
                rowErrors.add("姓名不能为空");
            }

            String email = cellText(excelRow, UserImportColumn.EMAIL, formatter).trim();
            if (StringUtils.hasText(email) && !EMAIL_PATTERN.matcher(email).matches()) {
                rowErrors.add("邮箱格式不正确");
            }

            String password = cellText(excelRow, UserImportColumn.PASSWORD, formatter).trim();
            if (!StringUtils.hasText(password)) {
                rowErrors.add("初始密码不能为空");
            }

            String className = cellText(excelRow, UserImportColumn.CLASS_NAME, formatter).trim();
            Long classId = null;
            if (!StringUtils.hasText(className)) {
                rowErrors.add("班级名称不能为空");
            } else {
                List<ClassEntity> matches = classMapper.selectList(Wrappers.<ClassEntity>lambdaQuery()
                        .eq(ClassEntity::getName, className));
                if (matches.isEmpty()) {
                    rowErrors.add("班级不存在: " + className);
                } else if (matches.size() > 1) {
                    rowErrors.add("班级名称存在歧义: " + className);
                } else {
                    classId = matches.get(0).getId();
                }
            }

            if (!rowErrors.isEmpty()) {
                result.getErrors().add(new UserImportResult.ErrorRow(excelLine, String.join("；", rowErrors)));
                result.setFailed(result.getFailed() + 1);
                continue;
            }

            final Long finalClassId = classId;
            final String finalEmail = StringUtils.hasText(email) ? email : null;
            try {
                transactionTemplate.execute(status -> {
                    User user = new User();
                    user.setUsername(username);
                    user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt(12)));
                    user.setName(name);
                    user.setEmail(finalEmail);
                    user.setStatus(0);
                    user.setMustChangePassword(0);
                    userService.insert(user);

                    Role studentRole = userService.getRoleByCode(RoleHierarchy.STUDENT);
                    if (studentRole == null) {
                        throw new BusinessException(ResponseCode.INTERNAL_ERROR, "学生角色未初始化");
                    }
                    userService.bindRole(user.getId(), studentRole.getId());

                    UserClass link = new UserClass();
                    link.setUserId(user.getId());
                    link.setClassId(finalClassId);
                    link.setJoinedTime(LocalDateTime.now());
                    userClassMapper.insert(link);

                    return null;
                });
                result.setImported(result.getImported() + 1);
            } catch (RuntimeException e) {
                log.warn("导入单行入库失败 line={} err={}", excelLine, e.getMessage());
                result.getErrors().add(new UserImportResult.ErrorRow(excelLine, "入库失败，请检查数据"));
                result.setFailed(result.getFailed() + 1);
            }
        }
        log.info("用户导入完成：imported={} failed={}", result.getImported(), result.getFailed());
        return result;
    }

    // ==================== 模板 ====================

    /**
     * 生成导入模板（xlsx 字节）。
     */
    public byte[] buildTemplate() {
        return ExcelSheetWriter.withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "学生导入模板");
            ExcelSheetWriter.writeHeader(sheet, (Object[]) UserImportColumn.headers());
            // 示例行
            Row exampleRow = sheet.createRow(1);
            exampleRow.createCell(UserImportColumn.USERNAME.cellIndex()).setCellValue("2024001");
            exampleRow.createCell(UserImportColumn.NAME.cellIndex()).setCellValue("张三");
            exampleRow.createCell(UserImportColumn.EMAIL.cellIndex()).setCellValue("zhangsan@exam.com");
            exampleRow.createCell(UserImportColumn.PASSWORD.cellIndex()).setCellValue("Pass1234");
            exampleRow.createCell(UserImportColumn.CLASS_NAME.cellIndex()).setCellValue("高一(1)班");
            ExcelSheetWriter.autoSize(sheet, UserImportColumn.columnCount());
        });
    }

    // ==================== 导出 ====================

    /**
     * 导出用户名单（xlsx 字节，SXSSF 流式）。
     *
     * @param scope   student|teacher|all
     * @param classId 可选过滤班级
     */
    public byte[] exportUsers(String scope, Long classId) {
        String normalizedScope = StringUtils.hasText(scope) ? scope.trim().toLowerCase() : "student";
        return switch (normalizedScope) {
            case "teacher" -> exportTeachers();
            case "all" -> exportAllUsers();
            default -> exportStudents(classId);
        };
    }

    private byte[] exportStudents(Long classId) {
        List<User> students;
        Map<Long, String> userClassNameMap = new LinkedHashMap<>();

        if (classId != null) {
            ClassEntity clazz = classMapper.selectById(classId);
            String className = clazz != null ? clazz.getName() : "";
            List<UserClass> links = userClassMapper.selectList(Wrappers.<UserClass>lambdaQuery()
                    .eq(UserClass::getClassId, classId)
                    .orderByAsc(UserClass::getUserId));
            if (links.isEmpty()) {
                students = Collections.emptyList();
            } else {
                List<Long> userIds = links.stream().map(UserClass::getUserId).toList();
                // M-06：>500 用户时分片 selectBatchIds，杜绝单条巨型 IN 查询
                students = batchSelectUsers(userIds);
                for (Long uid : userIds) {
                    userClassNameMap.put(uid, className);
                }
            }
        } else {
            Role studentRole = userService.getRoleByCode(RoleHierarchy.STUDENT);
            if (studentRole == null) {
                students = Collections.emptyList();
            } else {
                List<UserRole> urs = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                        .eq(UserRole::getRoleId, studentRole.getId()));
                if (urs.isEmpty()) {
                    students = Collections.emptyList();
                } else {
                    List<Long> userIds = urs.stream().map(UserRole::getUserId).toList();
                    // M-06：>500 用户时分片 selectBatchIds，杜绝单条巨型 IN 查询
                    students = batchSelectUsers(userIds);

                    // 加载班级名称
                    List<UserClass> allLinks = new ArrayList<>();
                    // M-06：user_class 归属查询同样按 500 一片，避免巨型 IN
                    forEachIdSlice(userIds, slice -> allLinks.addAll(
                            userClassMapper.selectList(Wrappers.<UserClass>lambdaQuery()
                                    .in(UserClass::getUserId, slice))));
                    if (!allLinks.isEmpty()) {
                        Set<Long> cids = allLinks.stream().map(UserClass::getClassId).collect(Collectors.toSet());
                        Map<Long, String> cNameMap = classMapper.selectBatchIds(cids).stream()
                                .collect(Collectors.toMap(ClassEntity::getId, ClassEntity::getName, (a, b) -> a));
                        Map<Long, List<String>> userClassList = new LinkedHashMap<>();
                        for (UserClass uc : allLinks) {
                            String cName = cNameMap.get(uc.getClassId());
                            if (cName != null) {
                                userClassList.computeIfAbsent(uc.getUserId(), k -> new ArrayList<>()).add(cName);
                            }
                        }
                        for (Map.Entry<Long, List<String>> entry : userClassList.entrySet()) {
                            userClassNameMap.put(entry.getKey(), String.join(";", entry.getValue()));
                        }
                    }
                }
            }
        }

        return ExcelSheetWriter.withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "学生名单");
            ExcelSheetWriter.writeHeader(sheet, (Object[]) UserImportColumn.headers());
            int idx = 1;
            for (User u : students) {
                Row row = sheet.createRow(idx++);
                // 学号/姓名/邮箱/班级名称是用户可控文本：公式注入清洗（M-01）
                row.createCell(UserImportColumn.USERNAME.cellIndex())
                        .setCellValue(ExcelSheetWriter.sanitizeFormula(u.getUsername()));
                row.createCell(UserImportColumn.NAME.cellIndex())
                        .setCellValue(u.getName() != null ? ExcelSheetWriter.sanitizeFormula(u.getName()) : "");
                row.createCell(UserImportColumn.EMAIL.cellIndex())
                        .setCellValue(u.getEmail() != null ? ExcelSheetWriter.sanitizeFormula(u.getEmail()) : "");
                row.createCell(UserImportColumn.PASSWORD.cellIndex()).setCellValue("");
                row.createCell(UserImportColumn.CLASS_NAME.cellIndex())
                        .setCellValue(ExcelSheetWriter.sanitizeFormula(userClassNameMap.getOrDefault(u.getId(), "")));
            }
            ExcelSheetWriter.autoSize(sheet, UserImportColumn.columnCount());
        });
    }

    private byte[] exportTeachers() {
        Role teacherRole = userService.getRoleByCode(RoleHierarchy.TEACHER);
        List<User> teachers;
        if (teacherRole == null) {
            teachers = Collections.emptyList();
        } else {
            List<UserRole> urs = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                    .eq(UserRole::getRoleId, teacherRole.getId()));
            if (urs.isEmpty()) {
                teachers = Collections.emptyList();
            } else {
                List<Long> userIds = urs.stream().map(UserRole::getUserId).toList();
                // M-06：>500 用户时分片 selectBatchIds，杜绝单条巨型 IN 查询
                teachers = batchSelectUsers(userIds);
            }
        }

        return ExcelSheetWriter.withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "教师名单");
            ExcelSheetWriter.writeHeader(sheet, "学号", "姓名", "邮箱", "角色");
            int idx = 1;
            for (User u : teachers) {
                Row row = sheet.createRow(idx++);
                // 用户名/姓名/邮箱是用户可控文本：公式注入清洗（M-01）
                row.createCell(0).setCellValue(ExcelSheetWriter.sanitizeFormula(u.getUsername()));
                row.createCell(1).setCellValue(u.getName() != null ? ExcelSheetWriter.sanitizeFormula(u.getName()) : "");
                row.createCell(2).setCellValue(u.getEmail() != null ? ExcelSheetWriter.sanitizeFormula(u.getEmail()) : "");
                row.createCell(3).setCellValue("教师");
            }
            ExcelSheetWriter.autoSize(sheet, 4);
        });
    }

    private byte[] exportAllUsers() {
        // M-06：先投影全部用户 ID（轻量列），再按 500 一片有界回表加载实体，
        // 与「orderByAsc(id) 全量 selectList」口径一致（batchSelectUsers 按 ID 升序返回），
        // 同时杜绝 user_role 归属查询的单条巨型 IN 列表。
        List<Long> uids = userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .select(User::getId)
                        .orderByAsc(User::getId))
                .stream().map(User::getId).toList();
        List<User> users = batchSelectUsers(uids);
        Map<Long, String> roleMap = new LinkedHashMap<>();
        if (!users.isEmpty()) {
            List<UserRole> userRoles = new ArrayList<>();
            forEachIdSlice(uids, slice -> userRoles.addAll(userRoleMapper.selectList(
                    Wrappers.<UserRole>lambdaQuery().in(UserRole::getUserId, slice))));
            if (!userRoles.isEmpty()) {
                Set<Long> rids = userRoles.stream().map(UserRole::getRoleId).collect(Collectors.toSet());
                Map<Long, Role> roles = roleMapper.selectBatchIds(rids).stream()
                        .collect(Collectors.toMap(Role::getId, r -> r, (a, b) -> a));
                Map<Long, List<String>> userRoleNames = new LinkedHashMap<>();
                for (UserRole ur : userRoles) {
                    Role r = roles.get(ur.getRoleId());
                    if (r != null) {
                        userRoleNames.computeIfAbsent(ur.getUserId(), k -> new ArrayList<>())
                                .add(formatRoleName(r.getCode()));
                    }
                }
                for (Map.Entry<Long, List<String>> entry : userRoleNames.entrySet()) {
                    roleMap.put(entry.getKey(), String.join(";", entry.getValue()));
                }
            }
        }

        return ExcelSheetWriter.withStreamingWorkbook(workbook -> {
            SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "全部用户");
            ExcelSheetWriter.writeHeader(sheet, "学号", "姓名", "邮箱", "角色");
            int idx = 1;
            for (User u : users) {
                Row row = sheet.createRow(idx++);
                // 用户名/姓名/邮箱是用户可控文本：公式注入清洗（M-01）
                row.createCell(0).setCellValue(ExcelSheetWriter.sanitizeFormula(u.getUsername()));
                row.createCell(1).setCellValue(u.getName() != null ? ExcelSheetWriter.sanitizeFormula(u.getName()) : "");
                row.createCell(2).setCellValue(u.getEmail() != null ? ExcelSheetWriter.sanitizeFormula(u.getEmail()) : "");
                row.createCell(3).setCellValue(roleMap.getOrDefault(u.getId(), "无角色"));
            }
            ExcelSheetWriter.autoSize(sheet, 4);
        });
    }

    private String formatRoleName(String roleCode) {
        if (roleCode == null) return "";
        return switch (roleCode.toUpperCase()) {
            case "ADMIN" -> "管理员";
            case "TEACHER" -> "教师";
            case "STUDENT" -> "学生";
            default -> roleCode;
        };
    }

    // ==================== 内部工具 ====================

    /**
     * 分批切片工具（M-06）：对 id 列表按 {@link #BATCH_SIZE} 一片循环切片并逐个消费。
     * 供 {@link #batchSelectUsers} 与 exportAllUsers 的 user_role 归属 IN 查询共用，
     * 杜绝单次超长 IN 列表（MySQL 报文上限 + 大对象堆积）。
     */
    private void forEachIdSlice(List<Long> ids, Consumer<List<Long>> sliceConsumer) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, ids.size());
            sliceConsumer.accept(ids.subList(from, to));
        }
    }

    /**
     * 有界分批按 ID 查询用户（M-06）：对传入 userIds 按 {@link #BATCH_SIZE} 一片循环调用
     * {@code userMapper.selectBatchIds(slice)} 并累加，最终按 ID 升序返回——
     * 与既有「一次 selectBatchIds + 按 ID 排序」口径完全一致，仅把巨型 IN 切成有界分片。
     */
    List<User> batchSelectUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<User> users = new ArrayList<>(userIds.size());
        forEachIdSlice(userIds, slice -> users.addAll(userMapper.selectBatchIds(slice)));
        users.sort(Comparator.comparing(User::getId));
        return users;
    }

    private Sheet parseSheet(byte[] bytes) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            return workbook.getSheetAt(0);
        } catch (IOException e) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "文件无法解析，请确认是有效的 xlsx 文件");
        }
    }

    private void verifyHeader(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "模板缺少表头行");
        }
        String[] expected = UserImportColumn.headers();
        for (int c = 0; c < expected.length; c++) {
            Cell cell = header.getCell(c);
            // 表头同样剥除首尾 Unicode 空白（M-02），全角空格版本的表头不误判为模板不符
            String actual = cell == null ? "" : stripWhitespace(cell.getStringCellValue());
            if (!actual.equals(expected[c])) {
                throw new BusinessException(ResponseCode.BAD_REQUEST,
                        "模板表头与当前模板不一致，请下载最新模板（第 " + (c + 1) + " 列应为「" + expected[c] + "」）");
            }
        }
    }

    private int countDataRows(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !isEmptyRow(row, formatter)) {
                count++;
            }
        }
        return count;
    }

    private boolean isEmptyRow(Row row, DataFormatter formatter) {
        return Arrays.stream(UserImportColumn.values()).map(UserImportColumn::cellIndex).allMatch(c -> {
            Cell cell = row.getCell(c);
            return cell == null || !StringUtils.hasText(formatter.formatCellValue(cell));
        });
    }

    private String cellText(Row row, UserImportColumn col, DataFormatter formatter) {
        Cell cell = row.getCell(col.cellIndex());
        // 统一剥除首尾 Unicode 空白（M-02）：学号/姓名/邮箱/班级名称的全角空格与 NBSP 不再干扰匹配
        return cell == null ? "" : stripWhitespace(formatter.formatCellValue(cell));
    }

    private static String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1);
    }
}
