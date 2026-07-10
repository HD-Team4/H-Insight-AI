// 로컬 테스트 전용 목업 서버 — S3에 업로드하지 말 것
// 대기 페이지 정적 파일 + 게이트키퍼 status API 목업을 같이 띄운다.
//
// 실행:  node waiting-room/mock-server.js
// 접속:  http://localhost:8931/index.html?redirect=/customer/products
//
// 호출할 때마다 앞 인원이 15명씩 줄어 8번째 폴링(~30초)에 READY가 되고,
// 페이지가 appOrigin(ALB) + redirect 경로로 이동하는 것까지 볼 수 있다.
const http = require("http");
const fs = require("fs");
const path = require("path");

const ROOT = __dirname;
const START_AHEAD = 120;    // 시작 시 앞에 남은 인원
const DROP_PER_CALL = 15;   // 폴링 1회당 줄어드는 인원
let calls = 0;

const TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8"
};

http.createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");

    if (url.pathname === "/api/waiting-room/status") {
        calls++;
        const ahead = Math.max(0, START_AHEAD - calls * DROP_PER_CALL);
        const body = ahead > 0
            ? { status: "WAITING", position: ahead + 1, ahead, etaSeconds: ahead * 4, token: "mock-token-abc" }
            : { status: "READY", position: 1, ahead: 0, etaSeconds: 0, token: "mock-token-abc" };
        console.log(`[api] call ${calls}:`, JSON.stringify(body));
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(body));
        return;
    }

    const file = url.pathname === "/" ? "/index.html" : url.pathname;
    const fp = path.join(ROOT, file);
    if (fs.existsSync(fp) && fs.statSync(fp).isFile()) {
        res.writeHead(200, { "Content-Type": TYPES[path.extname(fp)] || "text/plain" });
        res.end(fs.readFileSync(fp));
    } else {
        res.writeHead(404);
        res.end("not found: " + req.url);
    }
}).listen(8931, () => {
    console.log("mock server: http://localhost:8931/index.html?redirect=/customer/products");
});
