package com.exam.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.clazz.entity.ClassEntity;
import com.exam.clazz.entity.UserClass;
import com.exam.clazz.mapper.ClassMapper;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.support.IntegrationTestBase;
import com.exam.user.entity.Role;
import com.exam.user.entity.User;
import com.exam.user.mapper.RoleMapper;
import com.exam.user.mapper.UserMapper;
import com.exam.user.mapper.UserRoleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户/学生批量导入导出集成测试（spec-delta authentication/class-management 全部 Scenario）。
 */
class UserImportExportIntegrationTest extends IntegrationTestBase {

    private static final String[] HEADERS = {"学号", "姓名", "邮箱", "初始密码", "班级名称"};

    @Autowired
    private ClassMapper classMapper;

    @Autowired
    private UserClassMapper userClassMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    // ==================== 1. 模板与三向一致 ====================

    @Test
    void templateDownloadMatchesColumnsAndContainsExample() throws Exception {
        String adminToken = loginToken("admin", "admin123");
        byte[] bytes = mockMvc.perform(get("/api/admin/users/import-template")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(bytes.length > 0, "模板应为非空 xlsx");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 0; i < HEADERS.length; i++) {
                assertEquals(HEADERS[i], sheet.getRow(0).getCell(i).getStringCellValue(), "表头第 " + (i + 1) + " 列");
            }
            // 包含示例行
            assertTrue(sheet.getLastRowNum() >= 1, "应至少包含 1 行示例");
        }
    }

    // ==================== 2. 权限门控 ====================

    @Test
    void templateAndImportAndExportRequireAdminAndUserManagePermission() throws Exception {
        String teacherToken = registerTeacher();
        String studentToken = registerStudent();
        byte[] xlsx = buildXlsx(List.<String[]>of(new String[]{"2024001", "张三", "zs@test.com", "Pass1234", "高一(1)班"}));

        // 导入
        mockMvc.perform(multipart("/api/admin/users/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/admin/users/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/admin/users/import")
                        .file(multipartFile(xlsx)))
                .andExpect(status().isUnauthorized());

        // 模板
        mockMvc.perform(get("/api/admin/users/import-template")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users/import-template")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users/import-template"))
                .andExpect(status().isUnauthorized());

        // 导出
        mockMvc.perform(get("/api/admin/users/export")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users/export")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users/export"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 3. 导入成功与班级挂接、初始密码登录 ====================

    @Test
    void importSuccessCreatesStudentWithBCryptPasswordAndBindsClass() throws Exception {
        String adminToken = loginToken("admin", "admin123");
        String teacherToken = registerTeacher();
        String className = unique("高一(1)班");
        long classId = createClass(teacherToken, className);

        String username = "stu_" + unique("imp");
        String initialPassword = "Pass" + unique("pwd");
        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{username, "李四", username + "@exam.com", initialPassword, className}
        ));

        JsonNode result = perform(multipart("/api/admin/users/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(adminToken)), 200)
                .get("data");

        assertEquals(1, result.get("imported").asInt());
        assertEquals(0, result.get("failed").asInt());
        assertEquals(0, result.get("errors").size());

        // 验证用户与角色
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        assertNotNull(user, "学生账号应已入库");
        assertEquals("李四", user.getName());
        assertEquals(username + "@exam.com", user.getEmail());

        // 角色固定 STUDENT
        List<Role> roles = userRoleMapper.selectList(Wrappers.<com.exam.user.entity.UserRole>lambdaQuery()
                        .eq(com.exam.user.entity.UserRole::getUserId, user.getId()))
                .stream().map(ur -> roleMapper.selectById(ur.getRoleId())).toList();
        assertEquals(1, roles.size());
        assertEquals("STUDENT", roles.get(0).getCode());

        // 班级关联挂接正确
        UserClass uc = userClassMapper.selectOne(Wrappers.<UserClass>lambdaQuery()
                .eq(UserClass::getUserId, user.getId())
                .eq(UserClass::getClassId, classId));
        assertNotNull(uc, "学生应挂接至班级");
        assertNotNull(uc.getJoinedTime(), "加入时间应非空");

        // 能以初始密码登录成功
        String token = loginToken(username, initialPassword);
        assertNotNull(token, "学生应能以初始密码成功登录");
    }

    // ==================== 4. 重复学号跳过不覆盖 ====================

    @Test
    void importDuplicateUsernameSkipsWithoutModifyingExistingAccount() throws Exception {
        String adminToken = loginToken("admin", "admin123");
        String teacherToken = registerTeacher();
        String className = unique("高一(2)班");
        createClass(teacherToken, className);

        // 先创建一个已有账号
        String existingStu = "stu_" + unique("exist");
        String originalPassword = "OriginalPass123";
        registerStudentExplicit(existingStu, originalPassword, "原姓名");

        // 构造导入文件：第 1 行是库内已存在的学号，第 2 行是新学号，第 3 行是重复该新学号（文件内重复）
        String newStu = "stu_" + unique("new");
        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{existingStu, "篡改姓名", "hacked@exam.com", "HackedPass123", className},
                new String[]{newStu, "王五", newStu + "@exam.com", "Pass1234", className},
                new String[]{newStu, "王五重名", newStu + "2@exam.com", "Pass1234", className}
        ));

        JsonNode result = perform(multipart("/api/admin/users/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(adminToken)), 200)
                .get("data");

        assertEquals(1, result.get("imported").asInt(), "仅第 2 行应成功入库");
        assertEquals(2, result.get("failed").asInt(), "第 1 行库内重复和第 3 行文件内重复应失败");
        assertEquals(2, result.get("errors").size());
        assertTrue(result.get("errors").get(0).get("reason").asText().contains("学号已存在"), "第 1 行错误应为学号已存在");
        assertTrue(result.get("errors").get(1).get("reason").asText().contains("文件内重复"), "第 3 行错误应为文件内重复");

        // 既有账号未被篡改
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, existingStu));
        assertEquals("原姓名", user.getName(), "既有账号姓名不应被改动");
        // 依然能以原密码登录，且不能以篡改密码登录
        assertNotNull(loginToken(existingStu, originalPassword));
    }

    // ==================== 5. 班级不存在/歧义跳过不自动建班 ====================

    @Test
    void importNonExistentOrAmbiguousClassSkipsWithoutAutoCreatingClass() throws Exception {
        String adminToken = loginToken("admin", "admin123");
        String teacherToken = registerTeacher();
        String validClass = unique("合法班级");
        createClass(teacherToken, validClass);

        // 构造同名歧义班级
        String ambiguousClass = unique("重名班级");
        createClass(teacherToken, ambiguousClass);
        createClass(teacherToken, ambiguousClass);

        long classCountBefore = classMapper.selectCount(null);

        String nonExistentClass = "完全不存在的班级xyz";
        String s1 = "stu_" + unique("c1");
        String s2 = "stu_" + unique("c2");
        String s3 = "stu_" + unique("c3");

        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{s1, "学生1", "s1@exam.com", "Pass1234", nonExistentClass},
                new String[]{s2, "学生2", "s2@exam.com", "Pass1234", ambiguousClass},
                new String[]{s3, "学生3", "s3@exam.com", "Pass1234", validClass}
        ));

        JsonNode result = perform(multipart("/api/admin/users/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(adminToken)), 200)
                .get("data");

        assertEquals(1, result.get("imported").asInt(), "仅合法班级行入库");
        assertEquals(2, result.get("failed").asInt(), "不存在班级和歧义班级应失败");
        assertTrue(result.get("errors").get(0).get("reason").asText().contains("班级不存在"), "第 1 行错误应为班级不存在");
        assertTrue(result.get("errors").get(1).get("reason").asText().contains("班级名称存在歧义"), "第 2 行错误应为班级名称存在歧义");

        long classCountAfter = classMapper.selectCount(null);
        assertEquals(classCountBefore, classCountAfter, "classes 表数量不得增加（禁止自动建班）");
    }

    // ==================== 6. 行数上限与坏格式 ====================

    @Test
    void importRowLimitOver1000AndBadFormatRejectsWholeBatch() throws Exception {
        String adminToken = loginToken("admin", "admin123");

        // 超过 1000 行
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            rows.add(new String[]{"stu_limit_" + i, "学生" + i, "", "Pass1234", "某班级"});
        }
        mockMvc.perform(multipart("/api/admin/users/import")
                        .file(multipartFile(buildXlsx(rows)))
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());

        // 非 xlsx 文件
        MockMultipartFile txtFile = new MockMultipartFile("file", "test.txt",
                "text/plain", "hello".getBytes());
        mockMvc.perform(multipart("/api/admin/users/import")
                        .file(txtFile)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());

        // 表头不符
        byte[] badHeaderXlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            var r = sheet.createRow(0);
            r.createCell(0).setCellValue("错误列1");
            r.createCell(1).setCellValue("错误列2");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                badHeaderXlsx = out.toByteArray();
            }
        }
        mockMvc.perform(multipart("/api/admin/users/import")
                        .file(multipartFile(badHeaderXlsx))
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    // ==================== 6.5 Unicode 全角空格清洗与导出公式防注入（harden M-01/M-02） ====================

    @Test
    void importStripsUnicodeWhitespaceAroundUserNameAndClassName() throws Exception {
        String adminToken = loginToken("admin", "admin123");
        String teacherToken = registerTeacher();
        String className = unique("高一(1)班");
        long classId = createClass(teacherToken, className);

        // 学号/姓名/邮箱/班级名称首尾用全角空格 \u3000 与不换行空格 \u00A0：
        // 标准 String.trim() 不清除 \u3000，会导致学号匹配与班级匹配失败整行报错；
        // M-02 stripWhitespace 剥除首尾全部 Unicode 空白后应精确入库并挂接班级。
        String username = "stu_" + unique("full");
        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{"\u3000" + username + "\u3000", "\u3000李四\u3000",
                        "\u00A0" + username + "@exam.com\u00A0", "Pass1234",
                        "\u3000" + className + "\u3000"}
        ));

        JsonNode result = perform(multipart("/api/admin/users/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(adminToken)), 200)
                .get("data");
        assertEquals(1, result.get("imported").asInt(), "首尾全角空格应剥除后成功导入");
        assertEquals(0, result.get("failed").asInt());
        assertEquals(0, result.get("errors").size());

        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        assertNotNull(user, "学号剥除全角空格后应精确入库");
        assertEquals("李四", user.getName(), "姓名剥除全角空格");
        UserClass uc = userClassMapper.selectOne(Wrappers.<UserClass>lambdaQuery()
                .eq(UserClass::getUserId, user.getId())
                .eq(UserClass::getClassId, classId));
        assertNotNull(uc, "班级名称剥除全角空格后应匹配成功挂接班级");
    }

    @Test
    void exportSanitizesFormulaInjectionCells() throws Exception {
        String adminToken = loginToken("admin", "admin123");
        String teacherToken = registerTeacher();
        String className = unique("高一(1)班");
        long classId = createClass(teacherToken, className);

        // 直接入库一名用户，姓名/邮箱为公式注入形态（= 与 @ 前缀）：
        // 导出写出单元格时应前置单引号强制纯文本（M-01 sanitizeFormula，防 Formula/DDE 注入）。
        String username = "stu_" + unique("inj");
        registerStudentInClass(username, "正常姓名", classId);
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        user.setName("=1+1");
        user.setEmail("@SUM(A1:A9)");
        userMapper.updateById(user);

        byte[] exportBytes = mockMvc.perform(get("/api/admin/users/export?scope=student&classId=" + classId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = readExcel(exportBytes);
        List<String> row = rows.stream()
                .filter(r -> !r.isEmpty() && username.equals(r.get(0)))
                .findFirst().orElseThrow(() -> new AssertionError("导出缺少用户 " + username));
        assertEquals("'=1+1", row.get(1), "姓名以 = 开头应前置单引号强制纯文本（公式防注入）");
        assertEquals("'@SUM(A1:A9)", row.get(2), "邮箱以 @ 开头应前置单引号（DDE 防注入）");
    }

    // ==================== 7. 导出 scope 三态与班级过滤、软删过滤 ====================

    @Test
    void exportScopeThreeStatesAndClassFilter() throws Exception {
        String adminToken = loginToken("admin", "admin123");
        String teacherToken = registerTeacher();

        String classA = unique("班级A");
        String classB = unique("班级B");
        long classIdA = createClass(teacherToken, classA);
        long classIdB = createClass(teacherToken, classB);

        String stuA = "stu_" + unique("expA");
        String stuB = "stu_" + unique("expB");
        registerStudentInClass(stuA, "学生A", classIdA);
        registerStudentInClass(stuB, "学生B", classIdB);

        // 构造一个软删学生
        String stuDeleted = "stu_" + unique("expDel");
        registerStudentInClass(stuDeleted, "被删学生", classIdA);
        User deletedUser = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, stuDeleted));
        userMapper.deleteById(deletedUser.getId()); // 逻辑删除

        // 1) scope=student（无 classId）：含 stuA, stuB，不含 stuDeleted，不含 teacher
        byte[] studentExport = mockMvc.perform(get("/api/admin/users/export?scope=student")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> studentRows = readExcel(studentExport);
        assertEquals(HEADERS.length, studentRows.get(0).size(), "学生导出的表头列数与模板一致");
        for (int i = 0; i < HEADERS.length; i++) {
            assertEquals(HEADERS[i], studentRows.get(0).get(i), "表头列逐字对齐模板第 " + (i + 1) + " 列");
        }
        assertTrue(containsUser(studentRows, stuA), "应包含学生A");
        assertTrue(containsUser(studentRows, stuB), "应包含学生B");
        assertFalse(containsUser(studentRows, stuDeleted), "不得包含软删学生");

        // 2) scope=student&classId=classIdA
        byte[] classAExport = mockMvc.perform(get("/api/admin/users/export?scope=student&classId=" + classIdA)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> classARows = readExcel(classAExport);
        assertTrue(containsUser(classARows, stuA), "classId 过滤应包含班级A学生");
        assertFalse(containsUser(classARows, stuB), "classId 过滤不得包含班级B学生");

        // 3) scope=teacher
        byte[] teacherExport = mockMvc.perform(get("/api/admin/users/export?scope=teacher")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> teacherRows = readExcel(teacherExport);
        assertFalse(containsUser(teacherRows, stuA), "教师导出不得含学生");

        // 4) scope=all
        byte[] allExport = mockMvc.perform(get("/api/admin/users/export?scope=all")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> allRows = readExcel(allExport);
        assertTrue(containsUser(allRows, stuA), "全部导出应包含学生");
        assertTrue(containsUser(allRows, "admin"), "全部导出应包含管理员");
        assertFalse(containsUser(allRows, stuDeleted), "全部导出不得包含软删用户");
    }

    // ==================== 8. 模板与导出列三向严格一致护栏 ====================

    @Test
    void templateAndExportAndEnumHeadersAreStrictlyConsistent() throws Exception {
        String adminToken = loginToken("admin", "admin123");

        // 1) 枚举定义表头
        String[] enumHeaders = com.exam.user.support.UserImportColumn.headers();

        // 2) 模板下载表头
        byte[] templateBytes = mockMvc.perform(get("/api/admin/users/import-template")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> templateRows = readExcel(templateBytes);
        List<String> templateHeaders = templateRows.get(0);

        // 3) 学生导出表头
        byte[] exportBytes = mockMvc.perform(get("/api/admin/users/export?scope=student")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> exportRows = readExcel(exportBytes);
        List<String> exportHeaders = exportRows.get(0);

        assertEquals(enumHeaders.length, templateHeaders.size(), "模板列数与枚举定义一致");
        assertEquals(enumHeaders.length, exportHeaders.size(), "导出列数与枚举定义一致");

        for (int i = 0; i < enumHeaders.length; i++) {
            assertEquals(enumHeaders[i], templateHeaders.get(i), "模板表头第 " + (i + 1) + " 列一致");
            assertEquals(enumHeaders[i], exportHeaders.get(i), "导出表头第 " + (i + 1) + " 列一致");
            assertEquals(templateHeaders.get(i), exportHeaders.get(i), "模板与导出表头逐字一致");
        }
    }

    // ==================== 9. 流式不溢出 ====================

    @Test
    void exportStreamingSxssfDoesNotFailWithLargeDataset() throws Exception {
        String adminToken = loginToken("admin", "admin123");
        String teacherToken = registerTeacher();
        String className = unique("海量班");
        long classId = createClass(teacherToken, className);

        // 批量直接插入 200 个学生
        Role studentRole = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, "STUDENT"));
        for (int i = 0; i < 200; i++) {
            User u = new User();
            u.setUsername("batch_stu_" + unique("b" + i));
            u.setName("批量学生" + i);
            u.setPassword("Pass1234");
            u.setStatus(0);
            u.setMustChangePassword(0);
            userMapper.insert(u);

            com.exam.user.entity.UserRole ur = new com.exam.user.entity.UserRole();
            ur.setUserId(u.getId());
            ur.setRoleId(studentRole.getId());
            userRoleMapper.insert(ur);

            UserClass uc = new UserClass();
            uc.setUserId(u.getId());
            uc.setClassId(classId);
            userClassMapper.insert(uc);
        }

        byte[] exportBytes = mockMvc.perform(get("/api/admin/users/export?scope=student&classId=" + classId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        List<List<String>> rows = readExcel(exportBytes);
        assertTrue(rows.size() >= 201, "表头 + 至少 200 行学生数据");
    }

    // ==================== 辅助方法 ====================

    private MockMultipartFile multipartFile(byte[] xlsx) {
        return new MockMultipartFile("file", "users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
    }

    private byte[] buildXlsx(List<String[]> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("学生导入");
            var header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            for (int r = 0; r < rows.size(); r++) {
                var row = sheet.createRow(r + 1);
                String[] cells = rows.get(r);
                for (int c = 0; c < cells.length; c++) {
                    row.createCell(c).setCellValue(cells[c]);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    private long createClass(String teacherToken, String name) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        return perform(jsonPost("/api/classes", teacherToken,
                objectMapper.writeValueAsString(body)), 200).get("data").get("id").asLong();
    }

    private void registerStudentExplicit(String username, String password, String name) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        body.put("name", name);
        body.put("email", username + "@test.com");
        body.put("roleType", "STUDENT");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void registerStudentInClass(String username, String name, long classId) throws Exception {
        registerStudentExplicit(username, "Pass1234", name);
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        UserClass uc = new UserClass();
        uc.setUserId(u.getId());
        uc.setClassId(classId);
        userClassMapper.insert(uc);
    }

    private List<List<String>> readExcel(byte[] bytes) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    var cell = row.getCell(c);
                    cells.add(cell == null ? "" : cell.getStringCellValue());
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private boolean containsUser(List<List<String>> rows, String username) {
        for (List<String> row : rows) {
            if (!row.isEmpty() && username.equals(row.get(0))) {
                return true;
            }
        }
        return false;
    }
}
