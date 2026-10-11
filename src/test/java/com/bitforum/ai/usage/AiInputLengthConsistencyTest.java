package com.bitforum.ai.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bitforum.ai.dto.AiChatRequest;

import jakarta.validation.constraints.Size;

/**
 * S4：聊天输入长度上限必须"两处一致"的防回归测试。
 *
 * <p>HTTP 入口由 {@link AiChatRequest} 的 {@code @Size(max=...)} 把关，AI 链路内部由
 * {@code bitforum.ai.budget.max-input-chars} 把关。修复前两者分别是 2000 与 4000，
 * 内部那道闸门在对话入口**永远不可达**，两处对"超长输入"的口径也不一致。
 *
 * <p>这条测试直接比对"注解里的值"与"生效配置的值"，任何一边被单独改动都会失败。
 */
@SpringBootTest
class AiInputLengthConsistencyTest {

    @Autowired
    private AiTokenBudgetGuard budgetGuard;

    @Test
    void defaultInputLimitShouldMatchHttpDtoLimit() throws Exception {
        Field content = AiChatRequest.class.getDeclaredField("content");
        Size size = content.getAnnotation(Size.class);

        assertEquals(size.max(), budgetGuard.maxInputChars(),
                "HTTP DTO 的 @Size 上限与 bitforum.ai.budget.max-input-chars 必须一致");
        assertEquals(2000, budgetGuard.maxInputChars(),
                "S4 统一后的默认上限为 2000 字符");
    }
}
