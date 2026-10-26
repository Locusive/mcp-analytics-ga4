-- Standard SQL for GA4 BigQuery export. Run as one multi-statement script.
-- Replace YOUR_PROJECT.analytics_PROPERTY_ID in all three references.
-- Read SHOP_NOBI_METRIC_COVERAGE.md before interpreting rates.
-- Dates use the GA property's reporting timezone, not the query runner's timezone.
-- Narrow history_start to a known attribution start to bound scan cost. For lifetime
-- same-browser history use your full retained history. Incomplete history undercounts.
-- Intraday tables are used only when a daily table for that date does not exist.
-- Current-day results are provisional; late events can update daily tables for 3 days.
-- These are export query results, not automatically installed native GA charts.

DECLARE reporting_timezone STRING DEFAULT 'UTC'; -- Set to your GA property timezone.
DECLARE report_start DATE DEFAULT DATE_SUB(CURRENT_DATE(reporting_timezone), INTERVAL 7 DAY);
DECLARE report_end DATE DEFAULT DATE_SUB(CURRENT_DATE(reporting_timezone), INTERVAL 1 DAY);
DECLARE observation_end DATE DEFAULT CURRENT_DATE(reporting_timezone);
DECLARE history_start DATE DEFAULT DATE_SUB(report_start, INTERVAL 90 DAY);
DECLARE report_currency STRING DEFAULT 'USD'; -- Do not sum unconverted currencies.

CREATE TEMP FUNCTION text_param(params ANY TYPE, wanted STRING) AS (
  (SELECT COALESCE(value.string_value, CAST(value.int_value AS STRING))
   FROM UNNEST(params) WHERE key = wanted LIMIT 1)
);
CREATE TEMP FUNCTION number_param(params ANY TYPE, wanted STRING) AS (
  (SELECT COALESCE(value.double_value, CAST(value.int_value AS FLOAT64),
                   CAST(value.float_value AS FLOAT64), SAFE_CAST(value.string_value AS FLOAT64))
   FROM UNNEST(params) WHERE key = wanted LIMIT 1)
);

CREATE TEMP TABLE daily_tables AS
SELECT table_name
FROM `YOUR_PROJECT.analytics_PROPERTY_ID.INFORMATION_SCHEMA.TABLES`
WHERE REGEXP_CONTAINS(table_name, r'^events_\d{8}$');

CREATE TEMP TABLE activity AS
SELECT
  PARSE_DATE('%Y%m%d', event_date) AS activity_date,
  TIMESTAMP_MICROS(event_timestamp) AS occurred_at,
  event_name,
  COALESCE(text_param(event_params, 'server_name'), 'unknown') AS server_name,
  COALESCE(text_param(event_params, 'agent_name'), 'Unknown') AS agent_name,
  COALESCE(text_param(event_params, 'agent_class'), 'unknown') AS agent_class,
  COALESCE(text_param(event_params, 'source'), 'unknown') AS source,
  COALESCE(text_param(event_params, 'medium'), 'unknown') AS medium,
  COALESCE(text_param(event_params, 'channel'), 'unknown') AS channel,
  COALESCE(text_param(event_params, 'entry_point'), 'unknown') AS entry_point,
  text_param(event_params, 'method') AS method,
  text_param(event_params, 'tool_name') AS tool_name,
  text_param(event_params, 'outcome') AS outcome,
  text_param(event_params, 'search_id') AS search_id,
  text_param(event_params, 'conversation_id') AS conversation_id,
  text_param(event_params, 'browser_id') AS browser_id,
  text_param(event_params, 'other_conversation_id') AS other_conversation_id,
  text_param(event_params, 'query_hash') AS query_hash,
  text_param(event_params, 'query_text') AS query_text,
  text_param(event_params, 'transaction_id') AS transaction_id,
  text_param(event_params, 'currency') AS currency,
  text_param(event_params, 'store') AS store,
  text_param(event_params, 'link_type') AS link_type,
  text_param(event_params, 'placement') AS placement,
  -- A missing identity is not converted into a synthetic visitor.
  IF(text_param(event_params, 'identity_kind') = 'identified',
     COALESCE(CONCAT('user:', NULLIF(user_id, '')),
              CONCAT('client:', NULLIF(user_pseudo_id, ''))), NULL) AS visitor_id,
  COALESCE(number_param(event_params, 'request_count'), 0) AS request_count,
  COALESCE(number_param(event_params, 'tool_call_count'), 0) AS tool_call_count,
  COALESCE(number_param(event_params, 'ai_query_count'), 0) AS ai_query_count,
  COALESCE(number_param(event_params, 'search_count'), 0) AS search_count,
  COALESCE(number_param(event_params, 'message_count'), 0) AS message_count,
  COALESCE(number_param(event_params, 'click_count'), 0) AS click_count,
  COALESCE(number_param(event_params, 'order_count'), 0) AS order_count,
  COALESCE(number_param(event_params, 'value'), 0) AS revenue,
  COALESCE(number_param(event_params, 'item_count'), 0) AS item_count,
  COALESCE(number_param(event_params, 'cart_count'), 0) AS cart_count,
  COALESCE(number_param(event_params, 'product_view_count'), 0) AS product_view_count,
  COALESCE(number_param(event_params, 'query_pageview_count'), 0) AS query_pageview_count,
  COALESCE(number_param(event_params, 'pageview_count'), 0) AS pageview_count,
  COALESCE(number_param(event_params, 'active_seconds'), 0) AS active_seconds,
  COALESCE(number_param(event_params, 'page_count'), 0) AS page_count,
  COALESCE(number_param(event_params, 'converted_visit_count'), 0) AS converted_visit_count,
  number_param(event_params, 'duration_ms') AS duration_ms
FROM `YOUR_PROJECT.analytics_PROPERTY_ID.events_*`
WHERE
  (_TABLE_SUFFIX BETWEEN FORMAT_DATE('%Y%m%d', history_start) AND FORMAT_DATE('%Y%m%d', observation_end))
  OR (
    _TABLE_SUFFIX BETWEEN CONCAT('intraday_', FORMAT_DATE('%Y%m%d', history_start))
                      AND CONCAT('intraday_', FORMAT_DATE('%Y%m%d', observation_end))
    AND CONCAT('events_', SUBSTR(_TABLE_SUFFIX, 10)) NOT IN (SELECT table_name FROM daily_tables)
  );

-- 1. Core totals. Notification policy is encoded by request_count; raw Event count
-- can include notifications. Tools/call and recorded searches are separate populations.
SELECT server_name, agent_name, source, tool_name,
       SUM(request_count) AS requests,
       SUM(tool_call_count) AS tool_calls,
       SUM(ai_query_count) AS ai_query_activities,
       SUM(search_count) AS recorded_search_events,
       SUM(message_count) AS messages
FROM activity
WHERE activity_date BETWEEN report_start AND report_end
GROUP BY server_name, agent_name, source, tool_name;

-- 2. Distinct accepted searches. search_id must be host-generated and unique per server.
-- Missing IDs are reported below rather than all being merged into one fake search.
CREATE TEMP TABLE searches AS
SELECT * FROM activity
WHERE activity_date BETWEEN report_start AND report_end
  AND search_count > 0 AND search_id IS NOT NULL
QUALIFY ROW_NUMBER() OVER (PARTITION BY server_name, search_id ORDER BY occurred_at) = 1;

-- Purchases: host must emit one attributed aggregate per transaction_id and server.
-- If allocating a transaction across independent records, use distinct stable allocation
-- identifiers as transaction_id. Do not collapse valid attributed line allocations.
CREATE TEMP TABLE orders AS
SELECT * FROM activity
WHERE activity_date BETWEEN report_start AND report_end
  AND event_name = 'mcp_purchase' AND transaction_id IS NOT NULL
  AND currency = report_currency
QUALIFY ROW_NUMBER() OVER (PARTITION BY server_name, transaction_id ORDER BY occurred_at) = 1;

CREATE TEMP TABLE clicks_by_search AS
SELECT c.server_name, c.search_id, SUM(c.click_count) AS clicks
FROM activity AS c
JOIN searches AS s USING (server_name, search_id)
WHERE c.event_name = 'mcp_link_open'
  AND c.activity_date BETWEEN report_start AND report_end
  AND c.occurred_at >= s.occurred_at
GROUP BY c.server_name, c.search_id;

CREATE TEMP TABLE orders_by_search AS
SELECT o.server_name, o.search_id, SUM(o.order_count) AS orders,
       SUM(o.revenue) AS revenue, SUM(o.item_count) AS items
FROM orders AS o
JOIN searches AS s USING (server_name, search_id)
WHERE o.occurred_at >= s.occurred_at
GROUP BY o.server_name, o.search_id;

-- Query/source/channel CVR is orders/searches. CTR is clicked searches/searches.
-- This view uses searches and outcomes in the report range, not lifetime outcomes.
SELECT s.server_name, s.agent_name, s.source, s.channel,
       COUNT(*) AS searches,
       SUM(COALESCE(c.clicks, 0)) AS clicks,
       COUNTIF(c.clicks > 0) AS clicked_searches,
       SAFE_DIVIDE(COUNTIF(c.clicks > 0), COUNT(*)) AS ctr,
       SUM(COALESCE(o.orders, 0)) AS orders,
       SAFE_DIVIDE(SUM(COALESCE(o.orders, 0)), COUNT(*)) AS query_cvr,
       SUM(COALESCE(o.revenue, 0)) AS revenue,
       SAFE_DIVIDE(SUM(COALESCE(o.revenue, 0)), SUM(COALESCE(o.orders, 0))) AS aov,
       SAFE_DIVIDE(SUM(COALESCE(o.items, 0)), SUM(COALESCE(o.orders, 0))) AS items_per_order,
       report_currency AS currency
FROM searches AS s
LEFT JOIN clicks_by_search AS c USING (server_name, search_id)
LEFT JOIN orders_by_search AS o USING (server_name, search_id)
GROUP BY s.server_name, s.agent_name, s.source, s.channel;

-- 3. Overview visitor metrics. Identity must be supplied consistently on searches,
-- purchases and website events. COALESCE is not historical cross-device stitching.
-- Purchasers are identified query visitors with an attributed order for a report search.
SELECT
  (SELECT COUNT(DISTINCT visitor_id) FROM searches) AS identified_query_visitors,
  (SELECT COUNT(DISTINCT s.visitor_id) FROM searches AS s
   JOIN orders_by_search AS o USING (server_name, search_id) WHERE o.orders > 0) AS identified_purchasers,
  SAFE_DIVIDE(
    (SELECT COUNT(DISTINCT s.visitor_id) FROM searches AS s
     JOIN orders_by_search AS o USING (server_name, search_id) WHERE o.orders > 0),
    (SELECT COUNT(DISTINCT visitor_id) FROM searches)) AS overview_visitor_cvr,
  SAFE_DIVIDE((SELECT COALESCE(SUM(revenue), 0) FROM orders_by_search),
              (SELECT COUNT(DISTINCT visitor_id) FROM searches)) AS revenue_per_query_visitor,
  SAFE_DIVIDE((SELECT COUNT(DISTINCT visitor_id) FROM searches),
              (SELECT COUNT(DISTINCT visitor_id) FROM activity
               WHERE activity_date BETWEEN report_start AND report_end AND pageview_count > 0)) AS query_visitor_share,
  SAFE_DIVIDE((SELECT SUM(cart_count) FROM activity WHERE activity_date BETWEEN report_start AND report_end),
              (SELECT SUM(message_count) FROM activity WHERE activity_date BETWEEN report_start AND report_end)) AS cart_rate,
  SAFE_DIVIDE((SELECT SUM(product_view_count) FROM activity WHERE activity_date BETWEEN report_start AND report_end),
              (SELECT SUM(message_count) FROM activity WHERE activity_date BETWEEN report_start AND report_end)) AS product_views_per_query,
  (SELECT SUM(query_pageview_count) FROM activity WHERE activity_date BETWEEN report_start AND report_end) AS query_pageviews;

-- 4. AI activity history. This intentionally includes page topics that are not searches.
CREATE TEMP TABLE ai_history AS
SELECT * FROM activity
WHERE activity_date BETWEEN report_start AND report_end AND ai_query_count > 0;

CREATE TEMP TABLE browser_conversations AS
SELECT DISTINCT server_name, browser_id, conversation_id
FROM activity
WHERE browser_id IS NOT NULL AND conversation_id IS NOT NULL
  AND activity_date BETWEEN history_start AND observation_end;

-- Explicit browser-link events can supplement browser_id observations when the host
-- correlation pipeline links conversations after the original search was recorded.
CREATE TEMP TABLE other_conversations AS
SELECT a.server_name, a.conversation_id, b.conversation_id AS other_conversation_id
FROM browser_conversations AS a
JOIN browser_conversations AS b USING (server_name, browser_id)
WHERE a.conversation_id != b.conversation_id
UNION DISTINCT
SELECT server_name, conversation_id, other_conversation_id FROM activity
WHERE event_name = 'mcp_browser_link'
  AND conversation_id IS NOT NULL AND other_conversation_id IS NOT NULL
  AND conversation_id != other_conversation_id
UNION DISTINCT
SELECT server_name, other_conversation_id AS conversation_id, conversation_id AS other_conversation_id
FROM activity WHERE event_name = 'mcp_browser_link'
  AND conversation_id IS NOT NULL AND other_conversation_id IS NOT NULL
  AND conversation_id != other_conversation_id;

SELECT h.occurred_at, h.agent_name, h.query_text, h.query_hash,
       h.search_id, h.conversation_id,
       -- Includes later clicks through observation_end, outside report_end if needed.
       IF(h.conversation_id IS NULL OR h.event_name = 'mcp_page_request', NULL,
          (SELECT COALESCE(SUM(c.click_count), 0) FROM activity AS c
           WHERE c.server_name = h.server_name AND c.conversation_id = h.conversation_id
             AND c.event_name = 'mcp_link_open' AND c.occurred_at >= h.occurred_at
             AND c.activity_date <= observation_end)) AS links_opened_through_observation_end,
       IF(h.conversation_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM browser_conversations AS bc
            WHERE bc.server_name = h.server_name AND bc.conversation_id = h.conversation_id)
          AND NOT EXISTS (SELECT 1 FROM other_conversations AS oc
            WHERE oc.server_name = h.server_name AND oc.conversation_id = h.conversation_id), NULL,
          (SELECT COUNT(DISTINCT oc.other_conversation_id) FROM other_conversations AS oc
           WHERE oc.server_name = h.server_name AND oc.conversation_id = h.conversation_id)) AS same_browser_other_conversations
FROM ai_history AS h
ORDER BY h.occurred_at DESC;

-- 5. Engagement visits: one FINALIZED summary per identified visitor-day.
-- These are visitor-days, not GA Sessions or MCP transport sessions.
CREATE TEMP TABLE visitor_days AS
SELECT * FROM activity
WHERE activity_date BETWEEN report_start AND report_end
  AND event_name = 'mcp_visit' AND visitor_id IS NOT NULL
QUALIFY ROW_NUMBER() OVER (PARTITION BY server_name, visitor_id, activity_date ORDER BY occurred_at DESC) = 1;
SELECT COUNT(*) AS engagement_visits,
       AVG(active_seconds) AS average_active_seconds,
       AVG(page_count) AS average_pages,
       SAFE_DIVIDE(COUNTIF(converted_visit_count > 0), COUNT(*)) AS engagement_visit_cvr
FROM visitor_days;

-- 6. Per-store observed clicks, including link type and placement distinctions.
SELECT store, link_type, placement, SUM(click_count) AS clicks
FROM activity
WHERE activity_date BETWEEN report_start AND report_end AND event_name = 'mcp_link_open'
GROUP BY store, link_type, placement;

-- 7. Coverage checks. Missing IDs prevent exact joins; they must not be called zero.
SELECT
  COUNTIF(search_count > 0 AND search_id IS NULL) AS search_events_without_search_id,
  COUNTIF(event_name = 'mcp_link_open' AND search_id IS NULL) AS clicks_without_search_id,
  COUNTIF(event_name = 'mcp_purchase' AND transaction_id IS NULL) AS orders_without_transaction_id,
  COUNTIF(search_count > 0 AND visitor_id IS NULL) AS searches_without_identified_visitor,
  COUNTIF(ai_query_count > 0 AND conversation_id IS NULL) AS ai_activity_without_conversation_id
FROM activity
WHERE activity_date BETWEEN report_start AND report_end;
