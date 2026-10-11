#!/usr/bin/env bash
# =============================================================================
# examOnline 自签证书生成脚本（add-deployment-stack）
#
# 用途：本地以 HTTPS 起生产编排做全链路冒烟验证的真实 TLS 配置。
#       产物落 docker/certs/out/（server.crt / server.key / 其他派生文件），
#       该目录已 gitignore，证书绝不入版本库。
#
# 真实证书替换：依赖真实域名/环境，属后续人工动作，本案如实登记为非目标。
#   （替换 = 把正式证书放到 docker/certs/out/ 的同名文件名并重新起栈即可）
#
# 前置：宿主 Git Bash + openssl。nginx 挂载点见 docker-compose.prod.yml
# （./docker/certs/out/ -> /etc/nginx/certs/）。
#
# 跨环境可移植（MINGW/Git Bash vs Linux）：
#   - Git Bash 的 MSYS 路径转换会把 -subj 的 "/C=CN" 误判为盘符并改写（历史坑）。
#     处理：检测 MSYS 时用 cygpath -w 把输出路径归一为 Windows 形态，
#     并以 MSYS_NO_PATHCONV=1 单独关闭该命令的参数路径转换，使 -subj 原样透传。
#   - 非 MSYS（Linux/macOS）走通用绝对路径，行为与常规 openssl 一致。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/out}"
mkdir -p "$OUT_DIR"

CERT_FILE="$OUT_DIR/server.crt"
KEY_FILE="$OUT_DIR/server.key"

case "$(uname -s)" in
  MINGW*|MSYS*)
    # 输出路径换算为 Windows 形态，供 mingw openssl 写入；关闭该命令的参数路径转换以保护 -subj。
    OUT_WIN="$(cygpath -w "$OUT_DIR")"
    CERT_WIN="$OUT_WIN\\server.crt"
    KEY_WIN="$OUT_WIN\\server.key"
    OSSL_ENV=(env MSYS_NO_PATHCONV=1)
    ;;
  *)
    CERT_WIN="$CERT_FILE"
    KEY_WIN="$KEY_FILE"
    OSSL_ENV=()
    ;;
esac

echo "生成自签证书到 $OUT_DIR ..."

"${OSSL_ENV[@]}" openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout "$KEY_WIN" \
  -out "$CERT_WIN" \
  -subj "/C=CN/O=examOnline Dev/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

# 校验产物存在且为非空（防静默生成失败）
test -s "$CERT_FILE" || { echo "FAIL: $CERT_FILE 生成异常" >&2; exit 1; }
test -s "$KEY_FILE"  || { echo "FAIL: $KEY_FILE 生成异常" >&2; exit 1; }

echo "OK："
echo "  cert : $CERT_FILE"
echo "  key  : $KEY_FILE"
echo "（挂载到 nginx 的 /etc/nginx/certs/，见 docker-compose.prod.yml）"