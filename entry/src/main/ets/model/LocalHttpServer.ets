import { socket } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { mimeOf } from '../common/Mime';
import { FORCE_CANVAS, FORCE_WILL_READ_FREQUENTLY } from '../common/Const';
import { buildBootScript } from './CheatState';

const DOMAIN: number = 0x0000;
const TAG: string = 'LocalHttp';

/** 端口必须是固定的，否则 origin 变化会导致 localStorage 存档丢失 */
const CANDIDATE_PORTS: number[] = [18787, 18788, 18789, 18790];

let servedCount: number = 0;
let servedBytes: number = 0;
let lastStatAt: number = 0;

function noteServed(size: number): void {
  servedCount++;
  servedBytes += size;
  const now: number = Date.now();
  if (now - lastStatAt > 3000) {
    lastStatAt = now;
    hilog.info(DOMAIN, TAG, 'served %{public}d files, %{public}d KB',
      servedCount, Math.floor(servedBytes / 1024));
  }
}

function indexOfCrlfCrlf(buf: Uint8Array): number {
  for (let i = 0; i + 3 < buf.length; i++) {
    if (buf[i] === 13 && buf[i + 1] === 10 && buf[i + 2] === 13 && buf[i + 3] === 10) {
      return i;
    }
  }
  return -1;
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out: Uint8Array = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

/**
 * 本地 HTTP 服务器：把沙箱 game 目录以 http://127.0.0.1:<port> 提供出去。
 *
 * 相比 Web.onInterceptRequest（每个请求一次主线程 IPC，实测 ~55ms/请求），
 * 真实 HTTP 走网络栈，并发高、延迟低，并且内核会正常做 HTTP 缓存。
 */
export class LocalHttpServer {
  private gameDir: string;
  private server: socket.TCPSocketServer | null = null;
  private port: number = 0;

  constructor(gameDir: string) {
    this.gameDir = gameDir;
  }

  get baseUrl(): string {
    return 'http://127.0.0.1:' + this.port;
  }

  async start(): Promise<number> {
    const server: socket.TCPSocketServer = socket.constructTCPSocketServerInstance();
    let bound: number = 0;
    let lastErr: Error | null = null;
    for (let i = 0; i < CANDIDATE_PORTS.length; i++) {
      try {
        const addr: socket.NetAddress = { address: '127.0.0.1', port: CANDIDATE_PORTS[i], family: 1 };
        await server.listen(addr);
        bound = CANDIDATE_PORTS[i];
        break;
      } catch (err) {
        lastErr = err as Error;
        hilog.warn(DOMAIN, TAG, 'listen port %{public}d failed: %{public}s',
          CANDIDATE_PORTS[i], JSON.stringify(err));
      }
    }
    if (bound === 0) {
      throw lastErr !== null ? lastErr : new Error('cannot bind local http port');
    }
    // 游戏内 API 地址是 http://localhost:8001，这里也监听它，
    // 避免客户端连不上而等待超时（未知路径返回 404，游戏按离线逻辑处理）。
    try {
      const apiAddr: socket.NetAddress = { address: '127.0.0.1', port: 8001, family: 1 };
      await server.listen(apiAddr);
      hilog.info(DOMAIN, TAG, '%{public}s', 'also listening on 127.0.0.1:8001 (api stub)');
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'listen 8001 failed: %{public}s', JSON.stringify(err));
    }
    this.server = server;
    this.port = bound;
    server.on('connect', (conn: socket.TCPSocketConnection) => {
      this.handleConnection(conn);
    });
    server.on('error', (err: Error) => {
      hilog.error(DOMAIN, TAG, 'server error: %{public}s', JSON.stringify(err));
    });
    hilog.info(DOMAIN, TAG, 'local http server on %{public}s', this.baseUrl);
    return bound;
  }

  async stop(): Promise<void> {
    const s: socket.TCPSocketServer | null = this.server;
    this.server = null;
    if (s !== null) {
      try {
        await s.close();
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'close server failed: %{public}s', JSON.stringify(err));
      }
    }
  }

  private handleConnection(conn: socket.TCPSocketConnection): void {
    let buf: Uint8Array = new Uint8Array(0);
    conn.on('message', (info: socket.SocketMessageInfo) => {
      try {
        buf = concat(buf, new Uint8Array(info.message));
        buf = this.processBuffer(conn, buf);
      } catch (err) {
        hilog.error(DOMAIN, TAG, 'handle message error: %{public}s', JSON.stringify(err));
      }
    });
    conn.on('error', (err: Error) => {
      hilog.warn(DOMAIN, TAG, 'conn error: %{public}s', JSON.stringify(err));
    });
  }

  private processBuffer(conn: socket.TCPSocketConnection, buf: Uint8Array): Uint8Array {
    let data: Uint8Array = buf;
    while (true) {
      const headerEnd: number = indexOfCrlfCrlf(data);
      if (headerEnd < 0) {
        break;
      }
      const headerText: string = util.TextDecoder.create('utf-8')
        .decodeToString(data.subarray(0, headerEnd));
      const lines: string[] = headerText.split('\r\n');
      const reqLine: string[] = lines[0].split(' ');
      const method: string = reqLine.length > 0 ? reqLine[0] : '';
      const rawPath: string = reqLine.length > 1 ? reqLine[1] : '/';
      let keepAlive: boolean = true;
      for (let i = 1; i < lines.length; i++) {
        const lower: string = lines[i].toLowerCase();
        if (lower.startsWith('connection:') && lower.indexOf('close') >= 0) {
          keepAlive = false;
        }
      }
      data = data.subarray(headerEnd + 4);
      if (method !== 'GET' && method !== 'HEAD') {
        this.sendText(conn, 405, 'Method Not Allowed', keepAlive);
        continue;
      }
      this.servePath(conn, rawPath, method === 'HEAD', keepAlive);
    }
    return data;
  }

  private servePath(conn: socket.TCPSocketConnection, rawPath: string, headOnly: boolean,
    keepAlive: boolean): void {
    let path: string = rawPath;
    const q: number = path.indexOf('?');
    if (q >= 0) {
      path = path.substring(0, q);
    }
    const h: number = path.indexOf('#');
    if (h >= 0) {
      path = path.substring(0, h);
    }
    try {
      path = decodeURIComponent(path);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'decode path failed: %{public}s', path);
    }
    if (path.length === 0 || path === '/') {
      path = '/index.html';
    }
    if (path.indexOf('..') >= 0) {
      this.sendText(conn, 403, 'Forbidden', keepAlive);
      return;
    }

    // 作弊注入：按当前启用的作弊清单动态下发（游戏脚本运行前执行）
    if (path === '/__cheats__.js') {
      this.serveCheatScript(conn, keepAlive);
      return;
    }

    // 始终走补丁流程：注入作弊脚本标签 + 实验性补丁（强制 Canvas / willReadFrequently）
    if (path === '/index.html') {
      this.servePatchedIndex(conn, this.gameDir + path, keepAlive);
      return;
    }

    const filePath: string = this.gameDir + path;
    try {
      const stat: fs.Stat = fs.statSync(filePath);
      noteServed(stat.size);
      hilog.info(DOMAIN, TAG, 'GET %{public}s (%{public}d)', path, stat.size);
      const header: string = 'HTTP/1.1 200 OK\r\n' +
        'Content-Type: ' + mimeOf(filePath) + '\r\n' +
        'Content-Length: ' + stat.size + '\r\n' +
        'Cache-Control: ' + this.cacheControl(filePath) + '\r\n' +
        'Access-Control-Allow-Origin: *\r\n' +
        'Connection: ' + (keepAlive ? 'keep-alive' : 'close') + '\r\n' +
        '\r\n';
      if (headOnly || stat.size === 0) {
        conn.send({ data: header }).then(() => {
          if (!keepAlive) {
            conn.close();
          }
        });
        return;
      }
      const body: ArrayBuffer = new ArrayBuffer(stat.size);
      const fd: fs.File = fs.openSync(filePath, fs.OpenMode.READ_ONLY);
      try {
        fs.readSync(fd.fd, body);
      } finally {
        fs.closeSync(fd);
      }
      conn.send({ data: header }).then(() => {
        conn.send({ data: body }).then(() => {
          if (!keepAlive) {
            conn.close();
          }
        }).catch((err: Error) => {
          hilog.warn(DOMAIN, TAG, 'send body failed: %{public}s', JSON.stringify(err));
        });
      }).catch((err: Error) => {
        hilog.warn(DOMAIN, TAG, 'send header failed: %{public}s', JSON.stringify(err));
      });
    } catch (err) {
      this.sendText(conn, 404, 'Not Found', keepAlive);
    }
  }

  /** 构建注入脚本（按开关拼装），无内容时返回空串 */
  private buildInjectScript(): string {
    let js: string = '';
    if (FORCE_CANVAS) {
      js += 'var o=HTMLCanvasElement.prototype.getContext;' +
        'HTMLCanvasElement.prototype.getContext=function(t){' +
        'if(t==="webgl"||t==="webgl2"||t==="experimental-webgl"){' +
        'console.log("PATCH blocked "+t);return null;}' +
        'return o.apply(this,arguments);};';
    }
    if (FORCE_WILL_READ_FREQUENTLY) {
      js += 'var o2=HTMLCanvasElement.prototype.getContext;' +
        'HTMLCanvasElement.prototype.getContext=function(t,a){' +
        'if(t==="2d"){var n={};if(a){for(var k in a){n[k]=a[k];}}n.willReadFrequently=true;' +
        'return o2.call(this,t,n);}' +
        'return o2.apply(this,arguments);};';
    }
    if (js.length === 0) {
      return '';
    }
    return '<script>(function(){try{' + js +
      '}catch(e){console.log("PATCH err "+e);}})();<\/script>';
  }

  /** 动态下发作弊注入脚本：按当前启用清单生成，游戏启动前执行 */
  private serveCheatScript(conn: socket.TCPSocketConnection, keepAlive: boolean): void {
    try {
      const js: string = buildBootScript();
      // 空内容（未启用作弊）时直接返回空脚本，避免 encodeInto('') 抛错
      const arr: Uint8Array = js.length > 0
        ? util.TextEncoder.create('utf-8').encodeInto(js)
        : new Uint8Array(0);
      const buf: ArrayBuffer = new ArrayBuffer(arr.byteLength);
      new Uint8Array(buf).set(arr);
      const header: string = 'HTTP/1.1 200 OK\r\n' +
        'Content-Type: application/javascript; charset=utf-8\r\n' +
        'Content-Length: ' + arr.byteLength + '\r\n' +
        'Cache-Control: no-cache\r\n' +
        'Access-Control-Allow-Origin: *\r\n' +
        'Connection: ' + (keepAlive ? 'keep-alive' : 'close') + '\r\n' +
        '\r\n';
      conn.send({ data: header }).then(() => {
        conn.send({ data: buf }).then(() => {
          if (!keepAlive) {
            conn.close();
          }
        }).catch((err: Error) => {
          hilog.warn(DOMAIN, TAG, 'send cheat js failed: %{public}s', JSON.stringify(err));
        });
      }).catch((err: Error) => {
        hilog.warn(DOMAIN, TAG, 'send cheat header failed: %{public}s', JSON.stringify(err));
      });
      hilog.info(DOMAIN, TAG, 'served /__cheats__.js (%{public}d bytes)', arr.byteLength);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'serveCheatScript failed: %{public}s', JSON.stringify(err));
      this.sendText(conn, 500, 'Internal Error', keepAlive);
    }
  }

  /** 读取 index.html 并注入作弊脚本标签 + 实验脚本后返回 */
  private servePatchedIndex(conn: socket.TCPSocketConnection, filePath: string,
    keepAlive: boolean): void {
    try {
      const html: string = fs.readTextSync(filePath);
      // 作弊脚本始终注入（未启用时 /__cheats__.js 返回空内容），保证先于游戏脚本执行
      const inject: string = '<script src="/__cheats__.js"><\/script>' + this.buildInjectScript();
      const headIdx: number = html.indexOf('<head>');
      const patched: string = headIdx >= 0
        ? html.substring(0, headIdx + 6) + inject + html.substring(headIdx + 6)
        : inject + html;

      const arr: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(patched);
      const buf: ArrayBuffer = new ArrayBuffer(arr.byteLength);
      new Uint8Array(buf).set(arr);
      const header: string = 'HTTP/1.1 200 OK\r\n' +
        'Content-Type: text/html\r\n' +
        'Content-Length: ' + arr.byteLength + '\r\n' +
        'Cache-Control: no-cache\r\n' +
        'Connection: ' + (keepAlive ? 'keep-alive' : 'close') + '\r\n' +
        '\r\n';
      conn.send({ data: header }).then(() => {
        conn.send({ data: buf }).then(() => {
          if (!keepAlive) {
            conn.close();
          }
        }).catch((err: Error) => {
          hilog.warn(DOMAIN, TAG, 'send patched html failed: %{public}s', JSON.stringify(err));
        });
      });
      hilog.info(DOMAIN, TAG, '%{public}s', 'served patched index.html (cheat + force canvas)');
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'servePatchedIndex failed: %{public}s', JSON.stringify(err));
      this.sendText(conn, 500, 'Internal Error', keepAlive);
    }
  }

  private sendText(conn: socket.TCPSocketConnection, code: number, reason: string,
    keepAlive: boolean): void {
    const header: string = 'HTTP/1.1 ' + code + ' ' + reason + '\r\n' +
      'Content-Length: 0\r\n' +
      'Connection: ' + (keepAlive ? 'keep-alive' : 'close') + '\r\n' +
      '\r\n';
    conn.send({ data: header }).then(() => {
      if (!keepAlive) {
        conn.close();
      }
    }).catch((err: Error) => {
      hilog.warn(DOMAIN, TAG, 'sendText failed: %{public}s', JSON.stringify(err));
    });
  }

  private cacheControl(filePath: string): string {
    if (filePath.endsWith('.html') || filePath.endsWith('.htm') ||
      filePath.endsWith('.webmanifest') || filePath.endsWith('service-worker.js')) {
      return 'no-cache';
    }
    return 'max-age=31536000';
  }
}

let sharedServer: LocalHttpServer | null = null;
/** App 启动时调用：提前把本地服务跑起来，避免 Web 加载时端口未就绪 */
export function startSharedServer(filesDir: string): void {
  if (sharedServer !== null) {
    return;
  }
  const srv: LocalHttpServer = new LocalHttpServer(filesDir);
  sharedServer = srv;
  srv.start().catch((err: Error) => {
    hilog.error(DOMAIN, TAG, 'startSharedServer failed: %{public}s', JSON.stringify(err));
  });
}

/** 本地服务地址（未就绪返回空串） */
export function sharedServerBaseUrl(): string {
  return sharedServer !== null ? sharedServer.baseUrl : '';
}
