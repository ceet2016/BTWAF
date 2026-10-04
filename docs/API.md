# 堡塔云 WAF 接口清单（由 aawaf 源码自动提取）

路由规则：`/api/<module>/<action>`；module 与 action 由 Go 结构体名和方法名做 snake_case 转换得到（源码见 `core/http.go` 的 `RegisterModule` 与路由分发逻辑）。

统一约定：

- 请求方式：**POST**，body 为 JSON
- 响应包装：`{ "code": 0, "res": <data>, "nonce": <ts> }`，`code == 0` 表示成功，失败时 `res` 通常是错误描述字符串
- 鉴权：先 GET 后台入口页面建立会话 Cookie，再 POST `/api/user/login` 取 JWT；之后所有 `/api/*` 请求携带头部 `Authorization: bt<token>`
- 另有 API Key 方式（`waf_request_time` + `waf_request_token = md5(time + md5(api_key))`），但不含 `/api/config/` 系列

共 **315** 个接口，分布于 23 个模块。

| 模块 | 说明 | 源文件 |
|---|---|---|
| `acmesh` | 证书（ACME） | `acme.go` |
| `auth` | 授权 / 许可证 | `auth.go` |
| `config` | 后台系统设置 | `config.go` |
| `customize` | 自定义规则 | `customize.go` |
| `exclusive` | 专属规则 | `exclusive_rules.go` |
| `inset` | 内置规则集 | `ip_group.go` |
| `limit` | IP / URL / UA 黑白名单 | `limit.go` |
| `manm` | 人机验证 | `man_machine.go` |
| `monitor` | 监控 | `monitor.go` |
| `multi` | 多『主控』 | `Multi.go` |
| `nginx` | Nginx 管理 | `nginx.go` |
| `notification` | 告警通知 | `notification.go` |
| `opt_log` | 操作日志 | `optlog.go` |
| `overview` | 总览统计与地图 | `overview.go` |
| `replace` | 内容替换 | `replacement.go` |
| `report` | 攻击报表与日志 | `attack_report.go` |
| `speed` | 站点限速 / 访问控制 | `speed.go` |
| `sys` | 系统信息 | `system.go` |
| `user` | 账号、登录会话、版本升级 | `user.go` |
| `wafmaster` | WAF 主控 | `waf_master_dns.go` |
| `wafmasterload` | WAF 负载 | `waf_master_load.go` |
| `wafmastersite` | 防护站点管理 | `waf_master_site.go` |
| `wafrules` | 全局与站点防护规则 | `waf_rules.go` |


## /api/acmesh/ — 证书（ACME）

- `POST /api/acmesh/apply_cert`
- `POST /api/acmesh/check_bt_account_info`
- `POST /api/acmesh/get_bt_account_info`
- `POST /api/acmesh/renew_cert`
- `POST /api/acmesh/renewal_cert`

## /api/auth/ — 授权 / 许可证

- `POST /api/auth/activate_license`
- `POST /api/auth/check_nps_submitted`
- `POST /api/auth/create_order`
- `POST /api/auth/get_nps_questions`
- `POST /api/auth/info`
- `POST /api/auth/is_obtained_trial`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `POST /api/auth/mount_license`
- `POST /api/auth/obtain_trial`
- `POST /api/auth/order_status`
- `POST /api/auth/pricing`
- `POST /api/auth/submit_nps`
- `POST /api/auth/unactivated_licenses`
- `POST /api/auth/update`
- `POST /api/auth/userinfo`
- `POST /api/auth/uuid`

## /api/config/ — 后台系统设置

- `POST /api/config/check_two_auth`
- `POST /api/config/get_cert`
- `POST /api/config/get_config`
- `POST /api/config/get_two_auth`
- `POST /api/config/new_generate_certificate`
- `POST /api/config/set_admin_path`
- `POST /api/config/set_basic_auth`
- `POST /api/config/set_cert`
- `POST /api/config/set_domain`
- `POST /api/config/set_help`
- `POST /api/config/set_intercept_page`
- `POST /api/config/set_ip`
- `POST /api/config/set_language`
- `POST /api/config/set_open_api`
- `POST /api/config/set_port`
- `POST /api/config/set_pwd`
- `POST /api/config/set_pwd_expire`
- `POST /api/config/set_syslog`
- `POST /api/config/set_timeout`
- `POST /api/config/set_title`
- `POST /api/config/set_two_auth`
- `POST /api/config/set_warning_open`
- `POST /api/config/setntp`
- `POST /api/config/sync_date`
- `POST /api/config/title`

## /api/customize/ — 自定义规则

- `POST /api/customize/create`
- `POST /api/customize/create_with_block_log`
- `POST /api/customize/export`
- `POST /api/customize/export_example`
- `POST /api/customize/get_config_help`
- `POST /api/customize/import`
- `POST /api/customize/list`
- `POST /api/customize/parse_expression`
- `POST /api/customize/remove`
- `POST /api/customize/to_expression`
- `POST /api/customize/update`

## /api/exclusive/ — 专属规则

- `POST /api/exclusive/add_rules`
- `POST /api/exclusive/clear_count`
- `POST /api/exclusive/del_rules`
- `POST /api/exclusive/get_list`
- `POST /api/exclusive/rule_status`
- `POST /api/exclusive/update_rules`

## /api/inset/ — 内置规则集

- `POST /api/inset/del_ip_group`
- `POST /api/inset/edit_ip_group`
- `POST /api/inset/get_by_name_ip`
- `POST /api/inset/get_malicious_ip_list`
- `POST /api/inset/ip_list`
- `POST /api/inset/ip_name_list`
- `POST /api/inset/set_ip_group`

## /api/limit/ — IP / URL / UA 黑白名单

- `POST /api/limit/clear_data`
- `POST /api/limit/clear_ip`
- `POST /api/limit/clear_ua`
- `POST /api/limit/clear_url`
- `POST /api/limit/del_data`
- `POST /api/limit/edit_ip`
- `POST /api/limit/edit_ua`
- `POST /api/limit/edit_url`
- `POST /api/limit/get_ip`
- `POST /api/limit/get_ua`
- `POST /api/limit/get_url`
- `POST /api/limit/off_ip`
- `POST /api/limit/off_ua`
- `POST /api/limit/off_url`
- `POST /api/limit/set_into`
- `POST /api/limit/set_ip`
- `POST /api/limit/set_out`
- `POST /api/limit/set_ua`
- `POST /api/limit/set_url`

## /api/manm/ — 人机验证

- `POST /api/manm/add_rules`
- `POST /api/manm/clear_count`
- `POST /api/manm/del_rules`
- `POST /api/manm/get_list`
- `POST /api/manm/off_authtype`
- `POST /api/manm/update_rules`

## /api/monitor/ — 监控

- `POST /api/monitor/history`

## /api/multi/ — 多『主控』

- `POST /api/multi/menu_list`
- `POST /api/multi/node_list`
- `POST /api/multi/remove_role`
- `POST /api/multi/remove_user`
- `POST /api/multi/role_list`
- `POST /api/multi/update_role`
- `POST /api/multi/update_user`
- `POST /api/multi/update_user_password`
- `POST /api/multi/user_list`
- `POST /api/multi/website_list`

## /api/nginx/ — Nginx 管理

- `POST /api/nginx/add_domain`
- `POST /api/nginx/add_return_source_ip`
- `POST /api/nginx/add_tcp_load_balance`
- `POST /api/nginx/check_return_source_auth`
- `POST /api/nginx/clear_tcp_load_balance_count`
- `POST /api/nginx/create_site`
- `POST /api/nginx/del_domain`
- `POST /api/nginx/del_return_source_ip`
- `POST /api/nginx/del_tcp_load_balance`
- `POST /api/nginx/delete_all_site`
- `POST /api/nginx/delete_site`
- `POST /api/nginx/delete_ssl`
- `POST /api/nginx/deploy_cert`
- `POST /api/nginx/download_log`
- `POST /api/nginx/download_ssl`
- `POST /api/nginx/get_add_domain_info`
- `POST /api/nginx/get_all_domain`
- `POST /api/nginx/get_all_ssl`
- `POST /api/nginx/get_domain`
- `POST /api/nginx/get_domain_response_status`
- `POST /api/nginx/get_log_list`
- `POST /api/nginx/get_real_time_data`
- `POST /api/nginx/get_replace_open`
- `POST /api/nginx/get_return_source`
- `POST /api/nginx/get_site_log`
- `POST /api/nginx/get_site_setting`
- `POST /api/nginx/get_sites`
- `POST /api/nginx/get_ssl_info`
- `POST /api/nginx/get_ssl_protocols_and_ciphers`
- `POST /api/nginx/get_tcp_load_balance`
- `POST /api/nginx/get_user_config`
- `POST /api/nginx/install_cert`
- `POST /api/nginx/modify_proxyinfo`
- `POST /api/nginx/modify_resolver`
- `POST /api/nginx/modify_return_source_ip`
- `POST /api/nginx/modify_site`
- `POST /api/nginx/modify_site_info`
- `POST /api/nginx/modify_tcp_load_balance`
- `POST /api/nginx/remove_site_ssl`
- `POST /api/nginx/return_source`
- `POST /api/nginx/set_force_https`
- `POST /api/nginx/set_listen_ipv_six_by_site`
- `POST /api/nginx/set_ssl_protocols_or_ciphers`
- `POST /api/nginx/user_config`

## /api/notification/ — 告警通知

- `POST /api/notification/clear`
- `POST /api/notification/list`
- `POST /api/notification/update`

## /api/opt_log/ — 操作日志

- `POST /api/opt_log/list`

## /api/overview/ — 总览统计与地图

- `POST /api/overview/attack_map`
- `POST /api/overview/back_to_source`
- `POST /api/overview/count`
- `POST /api/overview/count_info`
- `POST /api/overview/error_request`
- `POST /api/overview/get_help_config`
- `POST /api/overview/get_map`
- `POST /api/overview/get_spider_infos`
- `POST /api/overview/get_status_map`
- `POST /api/overview/infos`
- `POST /api/overview/intercept_page`
- `POST /api/overview/malicious_info`
- `POST /api/overview/map`
- `POST /api/overview/map_new_branch`
- `POST /api/overview/map_new_total`
- `POST /api/overview/nginx_count`
- `POST /api/overview/restart`
- `POST /api/overview/set_ip_address`
- `POST /api/overview/set_status_map`
- `POST /api/overview/slow_request`
- `POST /api/overview/update_large_screen_text`
- `POST /api/overview/waf_large_screen_map`
- `POST /api/overview/waf_large_screen_qps`
- `POST /api/overview/website_flow`

## /api/replace/ — 内容替换

- `POST /api/replace/add_rules`
- `POST /api/replace/add_rules_info`
- `POST /api/replace/clear_hit`
- `POST /api/replace/delete_rules`
- `POST /api/replace/get_rules`
- `POST /api/replace/update_rules`

## /api/report/ — 攻击报表与日志

- `POST /api/report/attack_report_count`
- `POST /api/report/attack_report_ip_log`
- `POST /api/report/attack_report_ip_log_info`
- `POST /api/report/attack_report_log`
- `POST /api/report/attack_report_log_info`
- `POST /api/report/attack_report_uri`
- `POST /api/report/cc_list`
- `POST /api/report/delete_rule_hit_list`
- `POST /api/report/get_file_content`
- `POST /api/report/get_rule_hit_list`
- `POST /api/report/get_rule_hit_type_list`
- `POST /api/report/set_hit_type`
- `POST /api/report/unset_all_ip`
- `POST /api/report/unset_ip`

## /api/speed/ — 站点限速 / 访问控制

- `POST /api/speed/add_rules`
- `POST /api/speed/add_rules_info`
- `POST /api/speed/clear_cache`
- `POST /api/speed/clear_count`
- `POST /api/speed/cluster_get_list`
- `POST /api/speed/del_rules`
- `POST /api/speed/get_list`
- `POST /api/speed/get_rules_info`
- `POST /api/speed/open_rule_status`
- `POST /api/speed/update_rules`
- `POST /api/speed/update_site`

## /api/sys/ — 系统信息

- `POST /api/sys/get_system_info`

## /api/user/ — 账号、登录会话、版本升级

- `POST /api/user/check_status`
- `POST /api/user/check_two_auth`
- `POST /api/user/confirm_malicious_ip_share_plain`
- `POST /api/user/get_malicious_ip_share_plain_text`
- `POST /api/user/get_validate_code`
- `POST /api/user/latest_version`
- `POST /api/user/login`
- `POST /api/user/logout`
- `POST /api/user/profile`
- `POST /api/user/refresh`
- `POST /api/user/repair`
- `POST /api/user/restart`
- `POST /api/user/submit_claim_text`
- `POST /api/user/submit_nginx_error`
- `POST /api/user/update`
- `POST /api/user/update_malicious_ip`
- `POST /api/user/update_profile`

## /api/wafmaster/ — WAF 主控

- `POST /api/wafmaster/check_dns_domain`
- `POST /api/wafmaster/create_aliyun_record`
- `POST /api/wafmaster/create_api_key`
- `POST /api/wafmaster/create_domain_dns_record`
- `POST /api/wafmaster/create_record`
- `POST /api/wafmaster/delete_aliyun_record`
- `POST /api/wafmaster/delete_dns_record`
- `POST /api/wafmaster/delete_record`
- `POST /api/wafmaster/get_aliyun_dns_record`
- `POST /api/wafmaster/get_allow_line`
- `POST /api/wafmaster/get_dns_domain_list`
- `POST /api/wafmaster/get_dns_list`
- `POST /api/wafmaster/get_dns_manufacturer_list`
- `POST /api/wafmaster/get_dns_record`
- `POST /api/wafmaster/get_dns_status_by_domain`
- `POST /api/wafmaster/get_free_info`
- `POST /api/wafmaster/modify_aliyun_record`
- `POST /api/wafmaster/modify_record`
- `POST /api/wafmaster/update_dns_record`

## /api/wafmasterload/ — WAF 负载

- `POST /api/wafmasterload/create_load_balance`
- `POST /api/wafmasterload/delete_load_balance`
- `POST /api/wafmasterload/modify_load_balance`

## /api/wafmastersite/ — 防护站点管理

- `POST /api/wafmastersite/clear_site_log`
- `POST /api/wafmastersite/clear_site_logs`
- `POST /api/wafmastersite/create_site`
- `POST /api/wafmastersite/delete_site`
- `POST /api/wafmastersite/delete_ssl`
- `POST /api/wafmastersite/deploy_ssl`
- `POST /api/wafmastersite/download_log`
- `POST /api/wafmastersite/download_ssl`
- `POST /api/wafmastersite/get_all_ssl`
- `POST /api/wafmastersite/get_log_list`
- `POST /api/wafmastersite/get_parse_record_by_domain`
- `POST /api/wafmastersite/get_real_time_data`
- `POST /api/wafmastersite/get_site_all_node`
- `POST /api/wafmastersite/get_site_domain_parse`
- `POST /api/wafmastersite/get_site_id_and_name`
- `POST /api/wafmastersite/get_site_list`
- `POST /api/wafmastersite/get_site_log`
- `POST /api/wafmastersite/get_site_logs_size`
- `POST /api/wafmastersite/get_siteid_and_domains`
- `POST /api/wafmastersite/get_ssl_protocols`
- `POST /api/wafmastersite/modify_resolver`
- `POST /api/wafmastersite/modify_site`

## /api/wafrules/ — 全局与站点防护规则

- `POST /api/wafrules/add_http_header_len_rules`
- `POST /api/wafrules/add_regional_restrictions_rules`
- `POST /api/wafrules/add_user_limit`
- `POST /api/wafrules/apply_all`
- `POST /api/wafrules/apply_global_rules_to_site`
- `POST /api/wafrules/del_http_header_len_rules`
- `POST /api/wafrules/del_regional_restrictions_rules`
- `POST /api/wafrules/del_rewrite_url`
- `POST /api/wafrules/del_user_limit`
- `POST /api/wafrules/edit_user_limit`
- `POST /api/wafrules/get_global_rules`
- `POST /api/wafrules/get_http_header_len_rules`
- `POST /api/wafrules/get_http_method_type_rules`
- `POST /api/wafrules/get_regional_restrictions`
- `POST /api/wafrules/get_regional_restrictions_rules`
- `POST /api/wafrules/get_rules`
- `POST /api/wafrules/get_spider_rules`
- `POST /api/wafrules/get_user_limit`
- `POST /api/wafrules/modify_http_header_len_rules`
- `POST /api/wafrules/recover_default_config`
- `POST /api/wafrules/reset_regional_country_rules`
- `POST /api/wafrules/set_cc_rules`
- `POST /api/wafrules/set_cc_rules_wait`
- `POST /api/wafrules/set_cdn_headers`
- `POST /api/wafrules/set_cdn_rules`
- `POST /api/wafrules/set_crawler_infos`
- `POST /api/wafrules/set_file_scan_rules`
- `POST /api/wafrules/set_file_upload_rules`
- `POST /api/wafrules/set_http_method_type_rules`
- `POST /api/wafrules/set_hvv`
- `POST /api/wafrules/set_idc_deny`
- `POST /api/wafrules/set_malicious_ip`
- `POST /api/wafrules/set_mode_rules`
- `POST /api/wafrules/set_regional_restrictions_rules`
- `POST /api/wafrules/set_retry_rules`
- `POST /api/wafrules/set_rewrite_url`
- `POST /api/wafrules/set_single_uri`
- `POST /api/wafrules/set_site_run_mode`
- `POST /api/wafrules/set_smart_cc_rules`
- `POST /api/wafrules/set_user_limit`
- `POST /api/wafrules/simulate_attack`
- `POST /api/wafrules/simulated_attack`
- `POST /api/wafrules/update_spider_rules`
