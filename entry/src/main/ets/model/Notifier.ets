import { notificationManager } from '@kit.NotificationKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'Notifier';

/** 完成/失败通知 id（进度通知由下载后台任务自带） */
const ID_COMPLETE: number = 1001;
const ID_FAILED: number = 1002;

/**
 * 通知封装：
 * - 下载进度由 request.downloadFile 的 background 通知负责；
 * - 本类负责「通知权限申请」与「完成/失败」通知。
 */
export class Notifier {
  /** 是否已发起过权限申请：进入未下载态会多次触发，避免重复调用系统 API */
  private static permissionRequested: boolean = false;

  /** 申请通知权限（首次下载前调用；用户拒绝后不再弹窗） */
  static requestPermission(ctx: common.UIAbilityContext): void {
    if (Notifier.permissionRequested) {
      return;
    }
    Notifier.permissionRequested = true;
    notificationManager.requestEnableNotification(ctx)
      .then(() => {
        hilog.info(DOMAIN, TAG, '%{public}s', 'notification enabled');
      })
      .catch((err: BusinessError) => {
        // 失败（如瞬时错误）允许下次重试，避免一次失败后永久不再申请
        Notifier.permissionRequested = false;
        hilog.warn(DOMAIN, TAG, 'requestEnableNotification failed: %{public}s', JSON.stringify(err));
      });
  }

  /**
   * 清理本应用自己的完成/失败通知。
   * 不使用 cancelAll：后台下载任务的进度通知由 request.agent 管理，
   * 误清会让「接管下载」时刚重新 show 的通知又被取消。
   */
  static cancelOwn(): void {
    Notifier.cancel(ID_COMPLETE);
    Notifier.cancel(ID_FAILED);
  }

  private static cancel(id: number): void {
    notificationManager.cancel(id).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'cancel %{public}d failed: %{public}s', id, JSON.stringify(err));
    });
  }

  static publishComplete(): void {
    Notifier.publish(ID_COMPLETE, '离线资源下载完成', '点击开始运行');
  }

  static publishFailed(reason: string): void {
    Notifier.publish(ID_FAILED, '离线资源下载失败', reason.length > 0 ? reason : '点击重试');
  }

  private static publish(id: number, title: string, text: string): void {
    const request: notificationManager.NotificationRequest = {
      id: id,
      content: {
        notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
        normal: {
          title: title,
          text: text
        }
      }
    };
    notificationManager.publish(request).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'publish failed: %{public}s', JSON.stringify(err));
    });
  }
}
