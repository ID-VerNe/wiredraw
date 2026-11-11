<!-- 正文会过 check_pr_body.py：本机路径、内网地址、口令、序列号、配对 GUID 一律用占位符。
     示例里的单号一律写 #NN，不要写真实编号。 -->

## 问题

关闭方式：默认写 Refs #NN（合并后由发现者回签通过才关单）。
把 NN 换成真实编号——写在注释里不算，`scripts/check_pr_sections.py` 只认正文里
可见的关键词。门禁也接受 Closes，但 Closes 会被 GitHub 在 merge 时直接关掉 issue；
别在正文里贴带具体编号的 closing 短语（举例一律写 #NN）。

## 改动

## 测试

```text
粘贴门禁原始输出（python scripts/run_gates.py ci）
```

## 基线验证

<!-- 行为类改动：修复前如何复现（贴原始输出）；纯文档可写「不适用」 -->

## 门禁改动

<!-- 没动 scripts/gates.json 就写「无」 -->

## 影响面

## 验收对照
