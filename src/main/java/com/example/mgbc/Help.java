// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbc;

/**
 * The manual returned for {@code GET /bt/}.
 */
public final class Help {
	private Help() {
	}

	public static String text() {
		return """
				MGBaritoneConnector — Baritone 控制 (Fabric, Minecraft 26.2)
				============================================================
				挂载地址: http://127.0.0.1:3420/bt
				必需模组: MGHttpdProvider + Baritone 1.19.0+
				本模组把命令直接交给 Baritone API, 不经过聊天框。

				  GET  /bt/   返回本使用说明
				  POST /bt/   执行一条 Baritone 命令, 请求体为纯文本 (text/plain, UTF-8)

				命令
				------------------------------------------------------------
				  请求体就是一条 Baritone 命令, 和聊天框里输入 #<命令> 等价:
				    goal ~ ~ ~20
				    mine iron_ore
				    stop
				  也可以保留 # 或 bt 前缀:
				    #stop / bt stop / stop 等价
				  一次请求只执行一条命令; 多行请求返回 400。

				示例
				  curl -X POST --data-binary 'goal ~ ~ ~20' http://127.0.0.1:3420/bt
				  curl -X POST --data-binary '#stop'         http://127.0.0.1:3420/bt
				  curl -X POST --data-binary 'mine iron_ore' http://127.0.0.1:3420/bt
				  ./mgbt 'goal ~ ~ ~20'
				  ./mgbt stop
				  curl http://127.0.0.1:3420/bt/              # 本说明
				  curl http://127.0.0.1:3420/                 # 所有 endpoint

				返回
				  200  ok: <命令>
				  400  空命令 / 多行 / 未进世界 / 没有 primary Baritone / 未知命令 / API 失败
				  404  路径不存在
				  405  方法不允许
				  413  请求体过大 (>16KB)
				  500  内部错误

				注意
				  - 命令直接调用 Baritone API, 不经过聊天框, 也不发聊天包。
				  - Baritone 是本模组的必需依赖; 没装时 Fabric 会拒绝加载本模组。
				  - 命令输出 (例如 [Baritone] Goal: ...) 在聊天栏, 用 AdvancedInfoFetcher 的
				    GET http://127.0.0.1:3420/aif/msg 读取。
				  - 只有进入世界后 Baritone 才有 primary 实例; 标题界面返回 400。
				  - 在 shell 里给整条命令加引号, 否则 bash 会把 ~ 展开成 $HOME。
				""";
	}
}
