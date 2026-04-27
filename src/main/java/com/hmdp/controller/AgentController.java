package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 这个 RestClient 来自 DifyClientConfig。
     *
     * 它只负责一件事：让你的 Spring Boot 后端主动请求 Dify。
     * 也就是说，它对应的方向是：
     * 后端 /agent/chat -> Dify /chat-messages
     *
     * Dify 再调用你的 /agent/shops/nearby、/agent/vouchers/of-shop/{shopId}
     * 这类接口，是 Dify 工作流 HTTP 节点发起的另一条请求链路。
     */
    @Resource(name = "difyRestClient")
    private RestClient difyRestClient;

    /**
     * Agent 对话入口。
     *
     * 这个接口本身不直接查店铺、不直接查优惠券，也不直接写提示词结果。
     * 它的作用是把用户问题和当前页面上下文组装成 Dify 需要的请求格式，
     * 然后调用 Dify 的 /chat-messages 接口，让 Dify 工作流继续执行。
     *
     * 典型链路：
     * 1. 前端或测试工具请求 POST /agent/chat；
     * 2. 本方法用 RestClient 请求 Dify /chat-messages；
     * 3. Dify 工作流根据意图调用下面三个 Agent 工具接口；
     * 4. Dify 整理结果后返回；
     * 5. 本方法把 Dify 返回结果包装成 Result 返回。
     */
    @PostMapping("/chat")
    public Result chat(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String query = getString(request, "query");
        if (StrUtil.isBlank(query)) {
            return Result.fail("用户问题不能为空");
        }

        Map<String, Object> difyRequest = new LinkedHashMap<>();
        difyRequest.put("inputs", buildDifyInputs(request, authorization));
        difyRequest.put("query", query);
        difyRequest.put("response_mode", "streaming");
        putIfNotNull(difyRequest, "conversation_id", normalizeConversationId(request));
        difyRequest.put("user", resolveDifyUser(request));

        try {
            String difyStream = difyRestClient.post()
                    .uri("/chat-messages")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    // Agent Chat App 不支持 blocking，所以这里用 streaming。
                    // 请求体仍交给 Spring/Jackson 序列化，避免手写 JSON 出错。
                    .body(difyRequest)
                    .retrieve()
                    .body(String.class);

            return Result.ok(parseDifyStreamingResponse(difyStream));
        } catch (RestClientException e) {
            // Dify 调用失败时返回统一 Result，前端不用解析 Spring 默认异常页面。
            return Result.fail("调用 Dify 失败：" + e.getMessage());
        } catch (IllegalStateException e) {
            return Result.fail("解析 Dify 流式响应失败：" + e.getMessage());
        }
    }

    /**
     * Dify streaming 返回的是 SSE 文本流，不是普通 JSON。
     * 这里把多段 data: {...} 中的 answer 片段拼成完整回答，
     * 再组装成和 blocking 类似的 Map，保证前端仍然可以读取 data.answer。
     */
    private Map<String, Object> parseDifyStreamingResponse(String streamBody) {
        if (StrUtil.isBlank(streamBody)) {
            throw new IllegalStateException("Dify 返回的流式响应为空");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        StringBuilder answer = new StringBuilder();
        StringBuilder eventData = new StringBuilder();

        String[] lines = streamBody.split("\\R");
        for (String line : lines) {
            if (line.isBlank()) {
                handleDifySseData(eventData, answer, result);
                eventData.setLength(0);
                continue;
            }

            if (line.startsWith("data:")) {
                eventData.append(line.substring("data:".length()).trim()).append('\n');
            }
        }

        handleDifySseData(eventData, answer, result);
        result.put("event", "message");
        result.put("answer", answer.toString());
        return result;
    }

    @SuppressWarnings("unchecked")
    private void handleDifySseData(StringBuilder eventData, StringBuilder answer, Map<String, Object> result) {
        if (eventData == null || eventData.isEmpty()) {
            return;
        }

        String data = eventData.toString().trim();
        if (StrUtil.isBlank(data) || "[DONE]".equals(data)) {
            return;
        }

        try {
            Map<String, Object> chunk = OBJECT_MAPPER.readValue(data, Map.class);
            Object event = chunk.get("event");
            if ("error".equals(event)) {
                Object code = chunk.get("code");
                Object message = chunk.get("message");
                throw new IllegalStateException("Dify 返回错误事件：" + code + " " + message);
            }

            Object answerPart = chunk.get("answer");
            if (answerPart != null) {
                answer.append(answerPart);
            }

            copyIfPresent(chunk, result, "task_id");
            copyIfPresent(chunk, result, "id");
            copyIfPresent(chunk, result, "message_id");
            copyIfPresent(chunk, result, "conversation_id");
            copyIfPresent(chunk, result, "mode");
            copyIfPresent(chunk, result, "metadata");
            copyIfPresent(chunk, result, "created_at");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法解析 Dify SSE 数据片段：" + data, e);
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 查询附近店铺。
     *
     * 这个接口保留你已经跑通的能力，最终请求路径仍然是：
     * GET /agent/shops/nearby
     *
     * Dify 工作流调用时必须传数字 typeId，不能传“美食”“KTV”等中文。
     */
    @GetMapping("/shops/nearby")
    public Result nearbyShops(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    /**
     * 按店铺 ID 或店铺名称查询优惠券。
     *
     * 这个接口是给 Dify 工作流用的兼容入口：
     * 1. 如果传 shopId，优先按 shopId 查询优惠券；
     * 2. 如果没有 shopId，但传了 shopName，就先按店铺名称查店铺；
     * 3. 如果名称只匹配到一个店铺，就继续查这个店铺的优惠券；
     * 4. 如果名称匹配到多个店铺，不直接乱选，而是返回候选店铺，让用户选择具体店铺。
     *
     * 请求示例：
     * GET /agent/vouchers/of-shop?shopId=1
     * GET /agent/vouchers/of-shop?shopName=新白鹿
     */
    @GetMapping("/vouchers/of-shop")
    public Result queryVouchersOfShopByIdOrName(
            @RequestParam(value = "shopId", required = false) String shopIdText,
            @RequestParam(value = "shopName", required = false) String shopName
    ) {
        boolean hasShopId = StrUtil.isNotBlank(shopIdText);
        boolean hasShopName = StrUtil.isNotBlank(shopName);

        if (!hasShopId && !hasShopName) {
            return Result.fail("shopId 和 shopName 至少传一个");
        }

        // 1. 优先按 shopId 查询
        // 原因：ID 是唯一的，比名称更准确。
        if (hasShopId) {
            Long shopId = parsePositiveLong(shopIdText);
            if (shopId == null) {
                return Result.fail("shopId 必须是大于 0 的数字");
            }
            return voucherService.queryVoucherOfShop(shopId);
        }

        // 2. 没有 shopId 时，才按 shopName 查询
        shopName = shopName.trim();

        // 2.1 先做精确匹配
        List<Shop> exactShops = shopService.lambdaQuery()
                .eq(Shop::getName, shopName)
                .last("LIMIT 10")
                .list();

        if (exactShops.size() == 1) {
            return voucherService.queryVoucherOfShop(exactShops.get(0).getId());
        }

        if (exactShops.size() > 1) {
            return Result.ok(buildShopCandidates(shopName, exactShops));
        }

        // 2.2 精确匹配不到，再做模糊匹配
        List<Shop> fuzzyShops = shopService.lambdaQuery()
                .like(Shop::getName, shopName)
                .last("LIMIT 10")
                .list();

        if (fuzzyShops.isEmpty()) {
            return Result.fail("没有找到名称包含【" + shopName + "】的店铺，请换个店名，或者先查询附近店铺后再选择具体门店。");
        }

        if (fuzzyShops.size() == 1) {
            return voucherService.queryVoucherOfShop(fuzzyShops.get(0).getId());
        }

        // 2.3 匹配到多个店铺时，不能让大模型自己猜，必须让用户选
        return Result.ok(buildShopCandidates(shopName, fuzzyShops));
    }

    /**
     * 把字符串转成正整数 Long。
     *
     * 注意：
     * 这里不用 @RequestParam Long shopId 直接接收，
     * 是为了避免 Dify 传 shopId="" 时 Spring 直接参数转换失败。
     */
    private Long parsePositiveLong(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }

        try {
            Long id = Long.valueOf(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 构造多个候选店铺的返回结果。
     *
     * Dify 收到 MULTIPLE_SHOPS 后，不应该继续查优惠券，
     * 而是应该把候选店铺展示给用户，让用户选择具体 shopId。
     */
    private Map<String, Object> buildShopCandidates(String keyword, List<Shop> shops) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "MULTIPLE_SHOPS");
        data.put("needChooseShop", true);
        data.put("message", "找到多个匹配店铺，请让用户选择具体店铺后再查询优惠券。");
        data.put("keyword", keyword);

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Shop shop : shops) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shopId", shop.getId());
            item.put("shopName", shop.getName());
            item.put("address", shop.getAddress());
            item.put("typeId", shop.getTypeId());
            candidates.add(item);
        }

        data.put("candidates", candidates);
        return data;
    }
    /**
     * Agent 秒杀下单接口。
     *
     * 这个接口只做入口保护，不重写秒杀业务：
     * 1. 必须有登录用户，否则 UserHolder 为空，不能知道是谁在抢券；
     * 2. 必须 confirmed=true，防止大模型误判一句话就直接下单；
     * 3. 真正的库存校验、一人一单、Lua 原子扣减、Stream 异步落库，
     *    继续复用 IVoucherOrderService.seckillVoucher(voucherId)。
     */
    @PostMapping("/vouchers/seckill/{voucherId}")
    public Result seckillVoucher(
            @PathVariable("voucherId") Long voucherId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("voucherId 必须是大于 0 的数字");
        }

        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录后再抢券");
        }

        if (!Boolean.TRUE.equals(getBoolean(request, "confirmed"))) {
            return Result.fail("请先确认券名、门店、使用规则和一人一单限制");
        }

        Long orderId = voucherOrderService.seckillVoucher(voucherId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("status", "ACCEPTED");
        data.put("message", "秒杀请求已提交，订单会异步创建，请稍后查看结果");
        return Result.ok(data);
    }

    private Map<String, Object> buildDifyInputs(Map<String, Object> request, String authorization) {
        Map<String, Object> inputs = new LinkedHashMap<>();

        Object rawInputs = request == null ? null : request.get("inputs");
        if (rawInputs instanceof Map<?, ?> extraInputs) {
            // 允许前端传扩展 inputs，方便以后增加工作流变量。
            // key 转成字符串，是为了保证最终 JSON 字段名稳定。
            extraInputs.forEach((key, value) -> {
                if (key != null) {
                    inputs.put(key.toString(), value);
                }
            });
        }

        // 下面这些字段使用 snake_case，是为了直接对应 Dify Start 节点变量名。
        // 其中 selected_voucher_id 是秒杀最关键的字段，不能从用户中文问题里猜。
        putIfNotNull(inputs, "selected_shop_id", getLong(request, "selectedShopId"));
        putIfNotNull(inputs, "selected_shop_name", getString(request, "selectedShopName"));
        putIfNotNull(inputs, "selected_voucher_id", getLong(request, "selectedVoucherId"));
        putIfNotNull(inputs, "selected_voucher_name", getString(request, "selectedVoucherName"));
        putIfNotNull(inputs, "selected_voucher_rules", getString(request, "selectedVoucherRules"));
        inputs.put("seckill_confirmed", Boolean.TRUE.equals(getBoolean(request, "seckillConfirmed")));

        if (StrUtil.isNotBlank(authorization)) {
            // 这个 token 只建议用于学习演示：Dify HTTP 节点调用秒杀接口时需要带登录态。
            // 真实生产更推荐 Dify 只返回 CALL_SECKILL，最终下单仍由后端自己执行。
            inputs.put("authorization", authorization);
        }

        return inputs;
    }

    private String normalizeConversationId(Map<String, Object> request) {
        String conversationId = getString(request, "conversationId");
        // Dify 新会话可以不传 conversation_id；返回 null 后不会放进请求体。
        return StrUtil.isBlank(conversationId) ? null : conversationId;
    }

    private String resolveDifyUser(Map<String, Object> request) {
        if (UserHolder.getUser() != null && UserHolder.getUser().getId() != null) {
            return UserHolder.getUser().getId().toString();
        }

        String user = getString(request, "user");
        if (StrUtil.isNotBlank(user)) {
            return user;
        }

        return "anonymous";
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String getString(Map<String, Object> source, String key) {
        if (source == null || source.get(key) == null) {
            return null;
        }
        return source.get(key).toString();
    }

    private Long getLong(Map<String, Object> source, String key) {
        if (source == null || source.get(key) == null) {
            return null;
        }

        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            // 这里返回 null，而不是抛异常，是为了避免把中文券名误当成 ID 继续传给 Dify。
            return null;
        }
    }

    private Boolean getBoolean(Map<String, Object> source, String key) {
        if (source == null || source.get(key) == null) {
            return false;
        }

        Object value = source.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }

        return Boolean.valueOf(value.toString());
    }
}
