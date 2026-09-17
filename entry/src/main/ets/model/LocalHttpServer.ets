import { socket } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { mimeOf } from '../common/Mime';
import { FORCE_WILL_READ_FREQUENTLY, DEBUG_UI } from '../common/Const';
import { buildBootScript } from './CheatState';
import { buildSaveGuardScript } from './SaveGuard';

const DOMAIN: number = 0x0000;
const TAG: string = 'LocalHttp';

/**
 * 端口必须固定：origin 变化会导致 localStorage 存档丢失。
 * 因此不做多端口回退——18787 被占用时宁可启动失败，也不静默换端口。
 */
const PORT: number = 18787;

/** 请求头缓冲区上限：防止畸形/异常请求无限占用内存（正常 GET 头仅数百字节） */
const MAX_REQUEST_HEADER: number = 16 * 1024;

/** 发送文件的分块大小：避免大文件一次性读进内存，同时减少 send 次数 */
const SEND_CHUNK: number = 1024 * 1024;

/**
 * 小文件阈值：整块读入并把 header 与 body 合并成一次 send。
 * 游戏冷启动会请求数百个 JS/JSON 小文件，合并后每个文件少一次异步 send 调用；
 * 大文件仍走分块，避免为合并 header 而多拷贝一份 MB 级数据。
 */
const SMALL_FILE_MERGE: number = 64 * 1024;

/** 空闲连接超时：keep-alive 连接长时间无请求时主动关闭，避免异常客户端长期占用 */
const IDLE_TIMEOUT_MS: number = 60 * 1000;

let servedCount: number = 0;
let servedBytes: number = 0;
let lastStatAt: number = 0;

/** 复用的编解码器：decodeToString / encodeInto 均为无状态整段转换，可安全复用 */
const utf8Decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
const utf8Encoder: util.TextEncoder = util.TextEncoder.create('utf-8');

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

  /** 服务未就绪（端口尚未绑定）时返回空串，调用方据此回退默认地址 */
  get baseUrl(): string {
    return this.port > 0 ? 'http://127.0.0.1:' + this.port : '';
  }

  async start(): Promise<number> {
    const server: socket.TCPSocketServer = socket.constructTCPSocketServerInstance();
    const addr: socket.NetAddress = { address: '127.0.0.1', port: PORT, family: 1 };
    await server.listen(addr);
    const bound: number = PORT;
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
    let idleTimer: number = -1;
    // 串行化同一连接上的响应：HTTP 流水线下响应必须按请求顺序发出，
    // 否则并发 stat/read 会让响应交错。busy 期间到达的数据留在 buf，当前响应发完再处理。
    let busy: boolean = false;
    const clearIdle = (): void => {
      if (idleTimer >= 0) {
        clearTimeout(idleTimer);
        idleTimer = -1;
      }
    };
    const armIdle = (): void => {
      clearIdle();
      idleTimer = setTimeout(() => {
        idleTimer = -1;
        hilog.info(DOMAIN, TAG, '%{public}s', 'closing idle connection');
        try {
          conn.close();
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'close idle conn failed: %{public}s', JSON.stringify(err));
        }
      }, IDLE_TIMEOUT_MS);
    };

    const drain = async (): Promise<void> => {
      if (busy) {
        return;
      }
      busy = true;
      try {
        while (true) {
          const headerEnd: number = indexOfCrlfCrlf(buf);
          if (headerEnd < 0) {
            break;
          }
          const headerText: string = utf8Decoder.decodeToString(buf.subarray(0, headerEnd));
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
          buf = buf.subarray(headerEnd + 4);
          if (method !== 'GET' && method !== 'HEAD') {
            await this.sendText(conn, 405, 'Method Not Allowed', keepAlive);
            continue;
          }
          await this.servePath(conn, rawPath, method === 'HEAD', keepAlive);
        }
      } catch (err) {
        hilog.error(DOMAIN, TAG, 'drain error: %{public}s', JSON.stringify(err));
      } finally {
        busy = false;
      }
    };

    conn.on('close', () => {
      clearIdle();
    });
    conn.on('message', (info: socket.SocketMessageInfo) => {
      try {
        armIdle();
        buf = concat(buf, new Uint8Array(info.message));
        if (buf.length > MAX_REQUEST_HEADER) {
          hilog.warn(DOMAIN, TAG, 'request header too large (%{public}d), closing', buf.length);
          buf = new Uint8Array(0);
          clearIdle();
          conn.close();
          return;
        }
        drain().catch((err: Error) => {
          hilog.error(DOMAIN, TAG, 'drain failed: %{public}s', JSON.stringify(err));
        });
      } catch (err) {
        hilog.error(DOMAIN, TAG, 'handle message error: %{public}s', JSON.stringify(err));
      }
    });
    conn.on('error', (err: Error) => {
      clearIdle();
      hilog.warn(DOMAIN, TAG, 'conn error: %{public}s', JSON.stringify(err));
    });
    armIdle();
  }

  private async servePath(conn: socket.TCPSocketConnection, rawPath: string, headOnly: boolean,
    keepAlive: boolean): Promise<void> {
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
      await this.sendText(conn, 403, 'Forbidden', keepAlive);
      return;
    }

    // 作弊注入：按当前启用的作弊清单动态下发（游戏脚本运行前执行）
    if (path === '/__cheats__.js') {
      await this.serveCheatScript(conn, keepAlive);
      return;
    }

    // 始终走补丁流程：注入作弊脚本标签 + 实验性补丁（强制 Canvas / willReadFrequently）
    if (path === '/index.html') {
      await this.servePatchedIndex(conn, this.gameDir + path, headOnly, keepAlive);
      return;
    }

    const filePath: string = this.gameDir + path;
    await this.serveFile(conn, filePath, path, headOnly, keepAlive);
  }

  /**
   * 异步 stat 后发送：同步 statSync/readSync 都在主线程，资源请求密集时（音频/大 JS）
   * 会阻塞 UI，这里全部改为异步，读取完成后再发送。
   */
  private serveFile(conn: socket.TCPSocketConnection, filePath: string, path: string,
    headOnly: boolean, keepAlive: boolean): Promise<void> {
    return fs.stat(filePath).then((stat: fs.Stat) => {
      noteServed(stat.size);
      // 资源请求可达数百次/次加载：仅在调试时逐条打印，正式包只保留 noteServed 的聚合日志
      if (DEBUG_UI) {
        hilog.info(DOMAIN, TAG, 'GET %{public}s (%{public}d)', path, stat.size);
      }
      const header: string = 'HTTP/1.1 200 OK\r\n' +
        'Content-Type: ' + mimeOf(filePath) + '\r\n' +
        'Content-Length: ' + stat.size + '\r\n' +
        'Cache-Control: ' + this.cacheControl(filePath) + '\r\n' +
        'Access-Control-Allow-Origin: *\r\n' +
        'Connection: ' + (keepAlive ? 'keep-alive' : 'close') + '\r\n' +
        '\r\n';
      if (headOnly || stat.size === 0) {
        return this.sendHeaderOnly(conn, header, keepAlive);
      }
      return this.sendFile(conn, filePath, stat.size, header, keepAlive);
    }).catch((err: Error) => {
      return this.sendText(conn, 404, 'Not Found', keepAlive);
    });
  }

  /** 只发响应头（HEAD 请求 / 空文件） */
  private sendHeaderOnly(conn: socket.TCPSocketConnection, header: string,
    keepAlive: boolean): Promise<void> {
    return conn.send({ data: header }).then(() => {
      if (!keepAlive) {
        conn.close();
      }
    }).catch((err: Error) => {
      hilog.warn(DOMAIN, TAG, 'send header failed: %{public}s', JSON.stringify(err));
    });
  }

  /** header 与 body 合并成一次 send（小响应，减少异步调用） */
  private sendMerged(conn: socket.TCPSocketConnection, header: string, body: Uint8Array,
    keepAlive: boolean): Promise<void> {
    const head: Uint8Array = utf8Encoder.encodeInto(header);
    const out: ArrayBuffer = new ArrayBuffer(head.byteLength + body.byteLength);
    const view: Uint8Array = new Uint8Array(out);
    view.set(head, 0);
    view.set(body, head.byteLength);
    return conn.send({ data: out }).then(() => {
      if (!keepAlive) {
        conn.close();
      }
    }).catch((err: Error) => {
      hilog.warn(DOMAIN, TAG, 'sendMerged failed: %{public}s', JSON.stringify(err));
    });
  }

  private sendFile(conn: socket.TCPSocketConnection, filePath: string, size: number,
    header: string, keepAlive: boolean): Promise<void> {
    return fs.open(filePath, fs.OpenMode.READ_ONLY).then((fd: fs.File) => {
      if (size <= SMALL_FILE_MERGE) {
        return this.sendSmallFile(conn, fd, size, header, keepAlive);
      }
      return this.sendLargeFile(conn, fd, size, header, keepAlive);
    }).catch((err: Error) => {
      hilog.warn(DOMAIN, TAG, 'open file failed: %{public}s', JSON.stringify(err));
      return this.sendText(conn, 404, 'Not Found', keepAlive);
    });
  }

  /** 小文件：整块读入，header 与 body 拼成一次 send（少一次异步调用） */
  private sendSmallFile(conn: socket.TCPSocketConnection, fd: fs.File, size: number,
    header: string, keepAlive: boolean): Promise<void> {
    return new Promise<void>((resolve: () => void) => {
      let closed: boolean = false;
      const closeFd = (): void => {
        if (closed) {
          return;
        }
        closed = true;
        try {
          fs.closeSync(fd);
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'close fd failed: %{public}s', JSON.stringify(err));
        }
      };
      const done = (): void => {
        if (!keepAlive) {
          conn.close();
        }
        resolve();
      };
      const body: ArrayBuffer = new ArrayBuffer(size);
      fs.read(fd.fd, body).then((bytesRead: number) => {
        closeFd();
        if (bytesRead <= 0) {
          done();
          return;
        }
        const head: Uint8Array = utf8Encoder.encodeInto(header);
        const out: ArrayBuffer = new ArrayBuffer(head.byteLength + bytesRead);
        const view: Uint8Array = new Uint8Array(out);
        view.set(head, 0);
        view.set(new Uint8Array(body).subarray(0, bytesRead), head.byteLength);
        conn.send({ data: out }).then(() => {
          done();
        }).catch((err: Error) => {
          hilog.warn(DOMAIN, TAG, 'send small file failed: %{public}s', JSON.stringify(err));
          done();
        });
      }).catch((err: Error) => {
        hilog.warn(DOMAIN, TAG, 'read file failed: %{public}s', JSON.stringify(err));
        closeFd();
        this.sendText(conn, 500, 'Internal Error', keepAlive).then(() => {
          done();
        });
      });
    });
  }

  /** 大文件：分块读取后发送，不一次性读进内存 */
  private sendLargeFile(conn: socket.TCPSocketConnection, fd: fs.File, size: number,
    header: string, keepAlive: boolean): Promise<void> {
    return new Promise<void>((resolve: () => void) => {
      // 按文件大小分配：小文件不再各占 1MB，降低 GC 压力
      const buf: ArrayBuffer = new ArrayBuffer(Math.min(SEND_CHUNK, size));
      let sent: number = 0;
      let closed: boolean = false;
      const closeFd = (): void => {
        if (closed) {
          return;
        }
        closed = true;
        try {
          fs.closeSync(fd);
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'close fd failed: %{public}s', JSON.stringify(err));
        }
      };
      const finish = (): void => {
        closeFd();
        if (!keepAlive) {
          conn.close();
        }
        resolve();
      };
      const fail = (msg: string, err: Error): void => {
        hilog.warn(DOMAIN, TAG, '%{public}s: %{public}s', msg, JSON.stringify(err));
        closeFd();
        this.sendText(conn, 500, 'Internal Error', keepAlive).then(() => {
          resolve();
        });
      };
      const pump = (): void => {
        const want: number = Math.min(SEND_CHUNK, size - sent);
        fs.read(fd.fd, buf, { length: want }).then((bytesRead: number) => {
          if (bytesRead <= 0) {
            // 文件被截断等异常：按已发送量收尾，避免客户端一直等
            finish();
            return;
          }
          sent += bytesRead;
          const payload: ArrayBuffer = bytesRead === buf.byteLength ? buf : buf.slice(0, bytesRead);
          conn.send({ data: payload }).then(() => {
            if (sent >= size) {
              finish();
            } else {
              pump();
            }
          }).catch((err: Error) => {
            hilog.warn(DOMAIN, TAG, 'send body failed: %{public}s', JSON.stringify(err));
            closeFd();
            if (!keepAlive) {
              conn.close();
            }
            resolve();
          });
        }).catch((err: Error) => {
          fail('read file failed', err);
        });
      };
      conn.send({ data: header }).then(() => {
        pump();
      }).catch((err: Error) => {
        fail('send header failed', err);
      });
    });
  }

  /** 构建注入脚本（强制 2D canvas willReadFrequently），关闭时返回空串 */
  private buildInjectScript(): string {
    if (!FORCE_WILL_READ_FREQUENTLY) {
      return '';
    }
    const js: string = 'var o2=HTMLCanvasElement.prototype.getContext;' +
      'HTMLCanvasElement.prototype.getContext=function(t,a){' +
      'if(t==="2d"){var n={};if(a){for(var k in a){n[k]=a[k];}}n.willReadFrequently=true;' +
      'return o2.call(this,t,n);}' +
      'return o2.apply(this,arguments);};';
    return '<script>(function(){try{' + js +
      '}catch(e){console.log("PATCH err "+e);}})();<\/script>';
  }

  /** 动态下发作弊注入脚本：按当前启用清单生成，游戏启动前执行 */
  private serveCheatScript(conn: socket.TCPSocketConnection, keepAlive: boolean): Promise<void> {
    try {
      const js: string = buildBootScript();
      // 空内容（未启用作弊）时直接返回空脚本，避免 encodeInto('') 抛错
      const arr: Uint8Array = js.length > 0
        ? utf8Encoder.encodeInto(js)
        : new Uint8Array(0);
      const header: string = 'HTTP/1.1 200 OK\r\n' +
        'Content-Type: application/javascript; charset=utf-8\r\n' +
        'Content-Length: ' + arr.byteLength + '\r\n' +
        'Cache-Control: no-cache\r\n' +
        'Access-Control-Allow-Origin: *\r\n' +
        'Connection: ' + (keepAlive ? 'keep-alive' : 'close') + '\r\n' +
        '\r\n';
      hilog.info(DOMAIN, TAG, 'served /__cheats__.js (%{public}d bytes)', arr.byteLength);
      return this.sendMerged(conn, header, arr, keepAlive);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'serveCheatScript failed: %{public}s', JSON.stringify(err));
      return this.sendText(conn, 500, 'Internal Error', keepAlive);
    }
  }

  /** 读取 index.html 并注入存档守卫 + 作弊脚本标签 + 实验脚本后返回 */
  private servePatchedIndex(conn: socket.TCPSocketConnection, filePath: string,
    headOnly: boolean, keepAlive: boolean): Promise<void> {
    return fs.readText(filePath).then((html: string) => {
      // 存档守卫放最前：必须在游戏脚本读 localStorage 之前完成回填
      // 作弊脚本始终注入（未启用时 /__cheats__.js 返回空内容），保证先于游戏脚本执行
      const inject: string = buildSaveGuardScript() +
        '<script src="/__cheats__.js"><\/script>' + this.buildInjectScript();
      const headIdx: number = html.indexOf('<head>');
      const patched: string = headIdx >= 0
        ? html.substring(0, headIdx + 6) + inject + html.substring(headIdx + 6)
        : inject + html;

      const arr: Uint8Array = utf8Encoder.encodeInto(patched);
      const header: string = 'HTTP/1.1 200 OK\r\n' +
        'Content-Type: text/html\r\n' +
        'Content-Length: ' + arr.byteLength + '\r\n' +
        'Cache-Control: no-cache\r\n' +
        'Connection: ' + (keepAlive ? 'keep-alive' : 'close') + '\r\n' +
        '\r\n';
      if (headOnly) {
        return this.sendHeaderOnly(conn, header, keepAlive);
      }
      hilog.info(DOMAIN, TAG, '%{public}s', 'served patched index.html (cheat + force canvas)');
      return this.sendMerged(conn, header, arr, keepAlive);
    }).catch((err: Error) => {
      hilog.error(DOMAIN, TAG, 'servePatchedIndex failed: %{public}s', JSON.stringify(err));
      return this.sendText(conn, 500, 'Internal Error', keepAlive);
    });
  }

  private sendText(conn: socket.TCPSocketConnection, code: number, reason: string,
    keepAlive: boolean): Promise<void> {
    const header: string = 'HTTP/1.1 ' + code + ' ' + reason + '\r\n' +
      'Content-Length: 0\r\n' +
      'Connection: ' + (keepAlive ? 'keep-alive' : 'close') + '\r\n' +
      '\r\n';
    return conn.send({ data: header }).then(() => {
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
let sharedServerError: string = '';

/** App 启动时调用：提前把本地服务跑起来，避免 Web 加载时端口未就绪 */
export function startSharedServer(filesDir: string): void {
  if (sharedServer !== null) {
    return;
  }
  const srv: LocalHttpServer = new LocalHttpServer(filesDir);
  sharedServer = srv;
  srv.start().then(() => {
    sharedServerError = '';
  }).catch((err: Error) => {
    sharedServerError = JSON.stringify(err);
    hilog.error(DOMAIN, TAG, 'startSharedServer failed: %{public}s', sharedServerError);
  });
}

/** 本地服务地址（未就绪返回空串） */
export function sharedServerBaseUrl(): string {
  return sharedServer !== null ? sharedServer.baseUrl : '';
}

/** 本地服务是否已就绪（端口绑定成功）。端口被占用等情况下会一直为 false */
export function sharedServerReady(): boolean {
  return sharedServer !== null && sharedServer.baseUrl.length > 0;
}

/** 本地服务启动失败原因（未失败返回空串） */
export function sharedServerErrorText(): string {
  return sharedServerError;
}
