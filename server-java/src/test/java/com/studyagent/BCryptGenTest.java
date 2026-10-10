package com.studyagent.tool;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 临时工具类（不是单元测试，注意类名虽然叫 Test，但只有 main 方法，
 * 不会被 surefire 当作测试执行，也不依赖 Spring 上下文）。
 *
 * <p>用途：校验数据库 {@code sys_user} 表 demo 用户当前保存的 BCrypt 密文能否匹配明文 {@code 123456}；
 * 若不匹配，则输出一个可用的新密文。</p>
 *
 * <p>直接运行：</p>
 * <pre>java -cp "target/classes;target/test-classes;&lt;spring-security-crypto.jar&gt;" com.studyagent.tool.BCryptGenTest</pre>
 */
public final class BCryptGenTest {

    /** 【原始】数据库里 demo 用户曾经保存的密文（任务下发时给的值） */
    private static final String LEGACY_DB_HASH =
            "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2";

    /** 【当前】已经写入数据库的密文（由上面的 LEGACY 哈希校验失败后重新生成并更新） */
    private static final String CURRENT_DB_HASH =
            "$2a$10$2wkCrDJsWfTF4lAFvfinJe6A8Yc4nLEizy32iL3HXG6rY6X7M9lFa";

    /** 期望的明文密码 */
    private static final String RAW_PASSWORD = "123456";

    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        System.out.println("======================================================");
        System.out.println(" BCrypt 密文校验工具");
        System.out.println("======================================================");
        System.out.println("期望明文   : " + RAW_PASSWORD);
        System.out.println();

        // 0. 反向校验，确认不是恒等 true（用当前密文）
        System.out.println("反向校验（应全部为 false）：");
        System.out.println("    matches(\"1234567\" , CURRENT) = " + encoder.matches("1234567", CURRENT_DB_HASH));
        System.out.println("    matches(\"\"        , CURRENT) = " + encoder.matches("", CURRENT_DB_HASH));
        System.out.println("    matches(\"password\", CURRENT) = " + encoder.matches("password", CURRENT_DB_HASH));
        System.out.println("------------------------------------------------------");

        // 1. 校验【原始】密文
        boolean legacyMatched = safeMatches(encoder, RAW_PASSWORD, LEGACY_DB_HASH);
        System.out.println("[原始密文] " + LEGACY_DB_HASH);
        System.out.println("           length=" + LEGACY_DB_HASH.length()
                + "   matches(\"123456\") = " + legacyMatched);
        System.out.println();

        // 2. 校验【当前】密文
        boolean currentMatched = safeMatches(encoder, RAW_PASSWORD, CURRENT_DB_HASH);
        System.out.println("[当前密文] " + CURRENT_DB_HASH);
        System.out.println("           length=" + CURRENT_DB_HASH.length()
                + "   matches(\"123456\") = " + currentMatched);
        System.out.println("------------------------------------------------------");

        // 3. 结论
        System.out.println("结论汇总：");
        System.out.println("  1) 任务下发的原始密文匹配 123456 ？ -> " + (legacyMatched ? "是" : "【否】"));
        System.out.println("  2) 当前数据库中的密文匹配 123456 ？ -> " + (currentMatched ? "【是】" : "否"));
        if (!legacyMatched && currentMatched) {
            System.out.println();
            System.out.println("  原始密文与 123456 不匹配，已生成并写入新密文（见上方 [当前密文]）。");
            System.out.println("  对应 SQL：");
            System.out.println("  UPDATE sys_user SET password = '" + CURRENT_DB_HASH
                    + "' WHERE username = 'demo';");
        }
        System.out.println("======================================================");
    }

    private static boolean safeMatches(BCryptPasswordEncoder encoder, String raw, String hash) {
        try {
            return encoder.matches(raw, hash);
        } catch (Exception e) {
            System.out.println("[ERROR] matches 抛出异常：" + e.getClass().getName() + " : " + e.getMessage());
            return false;
        }
    }

    private BCryptGenTest() {
    }
}
