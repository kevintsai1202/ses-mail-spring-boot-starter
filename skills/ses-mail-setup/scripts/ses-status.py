r"""查詢 Amazon SES 帳號狀態：是否已離開沙箱、寄送是否啟用、24 小時額度與每秒上限、審核進度。

不用登入 AWS 主控台、不用裝 AWS CLI 或 boto3：只用 Python 標準庫，自己做 SigV4 簽章呼叫
SES v2 GetAccount（唯讀，只需要 ses:GetAccount 權限，應用程式的寄信金鑰就夠）。

金鑰依序取自：環境變數 AWS_SES_ACCESS_KEY_ID、AWS_SES_SECRET_ACCESS_KEY → --env-file 指定的檔案
→ 目前目錄的 .env。金鑰不會印出。

用法（PowerShell）：
    python ses-status.py
    python ses-status.py --region us-east-1 --env-file ..\.env
"""
import argparse
import datetime
import hashlib
import hmac
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

# GetAccount 的路徑
PATH = "/v2/email/account"
# 金鑰的環境變數名稱，與 ses-mail-spring-boot-starter 的設定範本一致
KEY_ID_VAR = "AWS_SES_ACCESS_KEY_ID"
SECRET_VAR = "AWS_SES_SECRET_ACCESS_KEY"


def load_env(path: pathlib.Path) -> dict:
    """讀取 .env 的 KEY=VALUE（忽略註解與空行，去掉引號）。"""
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def resolve_keys(env_file: str | None) -> tuple[str | None, str | None]:
    """依「環境變數 → --env-file → 目前目錄 .env」的順序取得金鑰。"""
    access_key = os.environ.get(KEY_ID_VAR)
    secret_key = os.environ.get(SECRET_VAR)
    if access_key and secret_key:
        return access_key, secret_key
    candidates = [pathlib.Path(env_file)] if env_file else [pathlib.Path.cwd() / ".env"]
    for candidate in candidates:
        if candidate.is_file():
            values = load_env(candidate)
            return values.get(KEY_ID_VAR), values.get(SECRET_VAR)
    return None, None


def sign(key: bytes, msg: str) -> bytes:
    """HMAC-SHA256。"""
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def get_account(region: str, access_key: str, secret_key: str) -> dict:
    """以 SigV4 簽章呼叫 SES v2 GetAccount，回傳 JSON。"""
    host = f"email.{region}.amazonaws.com"
    now = datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(b"").hexdigest()
    canonical_headers = f"host:{host}\nx-amz-date:{amz_date}\n"
    signed_headers = "host;x-amz-date"
    canonical_request = "\n".join(["GET", PATH, "", canonical_headers, signed_headers, payload_hash])
    scope = f"{date_stamp}/{region}/ses/aws4_request"
    string_to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope,
                                hashlib.sha256(canonical_request.encode("utf-8")).hexdigest()])
    k_signing = sign(sign(sign(sign(("AWS4" + secret_key).encode("utf-8"), date_stamp), region), "ses"),
                     "aws4_request")
    signature = hmac.new(k_signing, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    request = urllib.request.Request(f"https://{host}{PATH}", headers={
        "x-amz-date": amz_date,
        "Authorization": f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, "
                         f"SignedHeaders={signed_headers}, Signature={signature}",
    })
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.loads(response.read())


def main() -> int:
    """解析參數、取金鑰、查詢並印出重點欄位。"""
    parser = argparse.ArgumentParser(description="查詢 Amazon SES 帳號狀態（唯讀）")
    parser.add_argument("--region", default=os.environ.get("AWS_SES_REGION", "ap-northeast-1"),
                        help="SES 區域，預設取 AWS_SES_REGION，再不然是 ap-northeast-1")
    parser.add_argument("--env-file", help="含金鑰的 .env 檔；預設讀目前目錄的 .env")
    args = parser.parse_args()

    access_key, secret_key = resolve_keys(args.env_file)
    if not access_key or not secret_key:
        print(f"找不到金鑰：請設環境變數 {KEY_ID_VAR}、{SECRET_VAR}，或用 --env-file 指定 .env", file=sys.stderr)
        return 1
    try:
        account = get_account(args.region, access_key, secret_key)
    except urllib.error.HTTPError as error:
        # 403 多半是金鑰錯誤或缺 ses:GetAccount 權限
        print(f"GetAccount 失敗：HTTP {error.code} {error.read().decode('utf-8', 'replace')}", file=sys.stderr)
        return 1

    quota = account.get("SendQuota", {})
    details = account.get("Details", {})
    print(f"區域：{args.region}")
    print(f"生產存取權（已離開沙箱）：{account.get('ProductionAccessEnabled')}")
    print(f"寄送啟用：{account.get('SendingEnabled')}　帳號狀態：{account.get('EnforcementStatus')}")
    print(f"24 小時上限：{quota.get('Max24HourSend')}　已寄：{quota.get('SentLast24Hours')}"
          f"　每秒上限：{quota.get('MaxSendRate')}")
    review = details.get("ReviewDetails", {})
    if review:
        print(f"審核狀態：{review.get('Status')}　案件：{review.get('CaseId')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
