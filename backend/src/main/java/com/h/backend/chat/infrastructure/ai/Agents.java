package com.h.backend.chat.infrastructure.ai;

import com.h.backend.chat.infrastructure.ai.carrentalassistant.domain.StoryInfo;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.internal.Json;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Agents {

    public enum RequestCategory {
        //  法律类、医疗类、技术类、未知类
        LEGAL, MEDICAL, TECHNICAL, UNKNOWN
    }

    public interface CategoryRouter {

        @UserMessage("""
                分析下述用户请求并将其归类为'legal'(法律类)、'medical'(医疗类)或'technical'(技术类)。
                若请求不属于以上任一类别，则归类为'unknown'(未知类)。
                仅返回上述单词之一，不得附带其他内容。
                用户请求内容为：'{{request}}'。
                """)
        @Agent(description = "对用户请求进行分类", outputKey = "category")
        RequestCategory classify(@V("request") String request);
    }


    // --------------------------------------------

    /**
     * 重要的是分清你我他 历史你 历史我 历史他 当前你 当前我，当前的会话只有你我
     * 这个涉及到记忆管理，因为要在记忆里面分清楚才能正确回复
     */
    // ---------------------------------------------


    public interface MedicalExpert {

        @UserMessage("""
                你是一名医疗专业专家。
                从医学角度分析下方用户请求，并给出最优解答。
                用户请求内容：{{request}}。
                """)
        @Tool("医疗专家")
        @Agent(name = "医疗专家", description = "医疗专家", outputKey = "response")
        String medical(@MemoryId String memoryId, @V("request") String request, InvocationParameters parameters);

    }

    public interface LegalExpert {

        @UserMessage("""
                你是一名法律专业专家。
                从法律角度分析下方用户请求，并给出最优解答。
                用户请求内容：{{request}}。
                """)
        @Tool("法律专家")
        @Agent(name = "法律专家", description = "法律专家", outputKey = "response")
        String legal(@MemoryId String memoryId, @V("request") String request, InvocationParameters parameters);
    }

    public interface TechnicalExpert {

        @UserMessage("""
                    你是一名技术专家。
                    从技术层面分析下述用户请求，并给出最优解答。
                    用户请求内容：{{request}}。
                """)
        @Tool("技术专家")
        @Agent(name = "技术专家", description = "技术专家", outputKey = "response")
        String technical(@MemoryId String memoryId, @V("request") String request, InvocationParameters parameters);
    }


    public interface CreativeWriter {

        @UserMessage("""
                你是一名创意写作者。
                根据给定主题创作故事初稿，篇幅不超过三句话。
                仅返回故事内容，不输出其他任何文字。
                主题：{{topic}}。
                """)
        @Agent(name = "创意写作者", description = "根据指定主题生成故事", outputKey = "story")
        String generateStory(@V("topic") String topic, InvocationParameters parameters);
    }

    public interface AudienceEditor {

        @UserMessage("""
                    你是专业编辑。
                    分析并重写下方故事，使其更贴合{{audience}}目标受众。
                    仅返回修改后的故事，不输出其他内容。
                    原文故事："{{story}}"。
                """)
        @Agent(name = "受众编辑器", description = "修改故事适配指定受众群体", outputKey = "story")
        String editStory(@V("story") String story, @V("audience") String audience);
    }


    public interface StyleEditor {

        @UserMessage("""
                你是专业编辑。
                分析并重写下文故事，使其贴合{{style}}文风、行文更连贯统一。
                仅输出修改后的故事，不附带其他内容。
                原文故事："{{story}}"。
                """)
        @Agent(name = "风格编辑器", description = "调整故事适配指定文风", outputKey = "story")
        String editStory(@V("story") String story, @V("style") String style);
    }


    public interface StyleScorer {

        @UserMessage("""
                你是专业评审。
                根据故事与指定风格「{{style}}」的匹配程度给出0.0至1.0之间的评分。
                仅返回分数，不输出其他任何内容。
                故事原文："{{story}}"
                """)
        @Agent(name = "风格评分器", description = "依据故事与指定风格的契合度进行打分", outputKey = "score")
        double scoreStyle(@V("story") String story, @V("style") String style);
    }


    public interface StoryInfoAgent {
        @SystemMessage("""
                你是提取创作故事所需的相关信息助手，分析用户聊天信息并提取一下信息
                - 故事创造主题
                - 故事创造风格
                - 故事创造受众
                
                仅提取原文明确写明的内容，不得脑补推断，无对应信息则该项填null
                """)
        @UserMessage("""
                {{message}}
                """)
        @Agent(name = "故事信息提取器", description = "从客户聊天信息中提取创作故事所需的相关信息", outputKey = "storyInfo")
        StoryInfo extractStoryInfo(@MemoryId String memoryId, @V("message") String message);
    }

    public static class StoryInfoMapper {

        @Agent(name = "故事信息映射器", outputKey = "storyInfo")
        public StoryInfo map(@V("storyInfo") StoryInfo storyInfo, AgenticScope scope) {
            if (storyInfo != null) {
                scope.writeState("topic", storyInfo.getTopic());
                scope.writeState("style", storyInfo.getStyle());
                scope.writeState("audience", storyInfo.getAudience());
            }
            return storyInfo;
        }
    }

    public interface StoryChatAgent {

        ResultWithAgenticScope<String> chat(
                @MemoryId String memoryId,
                @V("message") String message,
                InvocationParameters parameters
        );
    }


    public interface BankerAgent {


        @SystemMessage(
                """
                        你是一名银行柜员，负责执行用户账户美元(USD)存入或支取操作。
                        """)
        @UserMessage(
                """
                        {{request}}
                        """)
        ResultWithAgenticScope<String> chat(@MemoryId String memoryId, @V("request") String request, InvocationParameters parameters);
    }

    public interface WithdrawAgent {
        @SystemMessage(
                """
                        你是一名银行柜员，仅能从用户账户支取美元（USD）。
                        """)
        @UserMessage(
                """
                        从 {{withdrawUser}} 的账户取出 {{amountInUSD}} 美元，返回更新后的账户余额。
                        """)
        @Agent(name = "负责账户美元取款业务的柜员", description = "负责账户美元取款业务的柜员")
        String withdraw(@V("withdrawUser") String withdrawUser, @V("amountInUSD") Double amountInUSD);
    }

    public interface CreditAgent {
        @SystemMessage(
                """
                        你是一名银行柜员，仅能为用户账户存入美元（USD）。
                        """)
        @UserMessage(
                """
                        为 {{creditUser}} 的账户存入 {{amountInUSD}} 美元，并返回最新余额。
                        """)
        @Agent(name = "负责为账户存入美元的柜员", description = "负责为账户存入美元的柜员")
        String credit(@V("creditUser") String creditUser, @V("amountInUSD") Double amountInUSD);
    }


    static class BankTool {

        private final Map<String, Double> accounts = new HashMap<>();

        void clearAccounts() {
            accounts.clear();
        }

        @Tool("创建指定用户账户，并设置初始余额")
        void createAccount(@P("user name") String user, @P("amount") Double initialBalance) {
            if (accounts.containsKey(user)) {
                throw new RuntimeException("Account for user " + user + " already exists");
            }
            accounts.put(user, initialBalance);
        }

        @Tool("获取指定用户账户余额")
        double getBalance(@P("user name") String user) {
            Double balance = accounts.get(user);
            if (balance == null) {
                throw new RuntimeException("No balance found for user " + user);
            }
            return balance;
        }

        @Tool("向指定用户账户存入对应金额，并返回最新账户余额")
        Double credit(@P("user name") String user, @P("amount") Double amount) {
            Double balance = accounts.get(user);
            if (balance == null) {
                throw new RuntimeException("No balance found for user " + user);
            }
            Double newBalance = balance + amount;
            accounts.put(user, newBalance);
            return newBalance;
        }

        @Tool("从指定用户账户支取对应金额，并返回最新账户余额")
        Double withdraw(@P("user name") String user, @P("amount") Double amount) {
            Double balance = accounts.get(user);
            if (balance == null) {
                throw new RuntimeException("No balance found for user " + user);
            }
            Double newBalance = balance - amount;
            accounts.put(user, newBalance);
            return newBalance;
        }
    }

    public interface ExchangeAgent {
        @UserMessage(
                """
                        你是一名货币兑换操作员。
                        调用工具将 {{amount}} 单位的 {{originalCurrency}} 兑换为 {{targetCurrency}}，
                        仅原样返回工具计算得出的最终金额，不输出其他任何内容。
                        """)
        @Agent(outputKey = "货币兑换智能体")
        Double exchange(
                @V("originalCurrency") String originalCurrency,
                @V("amount") Double amount,
                @V("targetCurrency") String targetCurrency);
    }

    static class ExchangeTool {

        public static Map<String, Double> exchangeRatesToUSD = new HashMap<>();

        static {
            exchangeRatesToUSD.put("USD", 1.0);
            exchangeRatesToUSD.put("EUR", 1.15);
            exchangeRatesToUSD.put("CHF", 1.25);
            exchangeRatesToUSD.put("CAN", 0.8);
        }

        @Tool("将指定金额的货币从原币种兑换为目标币种")
        Double exchange(
                @P("originalCurrency") String originalCurrency,
                @P("amount") Double amount,
                @P("targetCurrency") String targetCurrency) {
            Double exchangeRate1 = exchangeRatesToUSD.get(originalCurrency);
            if (exchangeRate1 == null) {
                throw new RuntimeException("No exchange rate found for currency " + originalCurrency);
            }
            Double exchangeRate2 = exchangeRatesToUSD.get(targetCurrency);
            if (exchangeRate2 == null) {
                throw new RuntimeException("No exchange rate found for currency " + targetCurrency);
            }
            return (amount * exchangeRate1) / exchangeRate2;
        }
    }

    public static class ExchangeOperator {

        public static Map<String, Double> exchangeRatesToUSD = new HashMap<>();

        static {
            exchangeRatesToUSD.put("USD", 1.0);
            exchangeRatesToUSD.put("EUR", 1.15);
            exchangeRatesToUSD.put("CHF", 1.25);
            exchangeRatesToUSD.put("CAN", 0.8);
        }

        @Agent(
                description =
                        "负责将指定金额货币从原币种兑换为目标币种的货币兑换员",
                outputKey = "exchange")
        public Double exchange(
                @V("originalCurrency") String originalCurrency,
                @V("amount") Double amount,
                @V("targetCurrency") String targetCurrency) {
            Double exchangeRate1 = exchangeRatesToUSD.get(originalCurrency);
            if (exchangeRate1 == null) {
                throw new RuntimeException("No exchange rate found for currency " + originalCurrency);
            }
            Double exchangeRate2 = exchangeRatesToUSD.get(targetCurrency);
            if (exchangeRate2 == null) {
                throw new RuntimeException("No exchange rate found for currency " + targetCurrency);
            }
            return (amount * exchangeRate1) / exchangeRate2;
        }
    }

    public interface FoodExpert {

        @UserMessage(""" 
                    你是专业的晚餐规划师，根据给定情绪推荐三份餐食清单。
                    情绪参数：{{mood}}。
                    每份餐食仅输出菜品名称，只返回包含三份餐食的列表，不输出其他内容。
                """)
        @Agent(name = "晚餐规划师", description = "根据给定情绪推荐三份餐食清单", outputKey = "meals")
        List<String> findMeal(@V("mood") String mood);
    }

    public interface MovieExpert {

        @UserMessage("""
                你是资深晚间活动规划师，根据指定情绪推荐三部影片。
                情绪值：{{mood}}。
                仅返回包含三部影片名称的列表，不输出额外内容。
                """)
        @Agent(name = "晚间活动规划师", description = "根据给定情绪推荐三部影片", outputKey = "movies")
        List<String> findMovie(@V("mood") String mood);
    }

    public record EveningPlan(String movie, String meal) {
    }

    public interface EveningPlannerAgent {

        @Agent(name = "晚间活动规划师", description = "根据给定情绪推荐三部影片和三份餐食")
        ResultWithAgenticScope<String> chat(@MemoryId String memoryId, @V("mood") String mood, InvocationParameters parameters);
    }


    public interface EveningPlannerAgentWithOutput extends EveningPlannerAgent {

        @Output
        static String createPlans(@V("movies") List<String> movies, @V("meals") List<String> meals) {
            List<EveningPlan> moviesAndMeals = new ArrayList<>();
            for (int i = 0; i < Math.min(movies.size(), meals.size()); i++) {
                moviesAndMeals.add(new EveningPlan(movies.get(i), meals.get(i)));
            }
            return Json.toJson(moviesAndMeals);
        }
    }


}
