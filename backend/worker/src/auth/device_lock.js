/**
 * DeviceLock: Handles hardware binding to protect seller subscriptions.
 * Supports single-device (1), multi-device (N), and unlimited (0 or -1).
 */

export class DeviceLock {
  /**
   * Verifies or binds a device ID to the user record.
   * Enforces device limits:
   *  - max_devices = 1: Single phone only (default)
   *  - max_devices = N (e.g. 20): Up to N distinct phones
   *  - max_devices <= 0: Unlimited phones
   */
  static verifyOrBind(userRecord, incomingDeviceId) {
    if (!incomingDeviceId) {
      return {
        ok: true,
        updatedRecord: null
      };
    }

    // Normalize devices array (backward compatible with single device_id string)
    let devices = Array.isArray(userRecord.devices) ? [...userRecord.devices] : [];
    if (userRecord.device_id && !devices.includes(userRecord.device_id)) {
      devices.unshift(userRecord.device_id);
    }

    const maxDevices = (userRecord.max_devices !== undefined && userRecord.max_devices !== null && userRecord.max_devices !== '')
      ? parseInt(userRecord.max_devices, 10)
      : 1;

    const isUnlimited = (maxDevices <= 0);

    // Case 1: Device is already recognized and authorized
    if (devices.includes(incomingDeviceId)) {
      return {
        ok: true,
        updatedRecord: null
      };
    }

    // Case 2: Unlimited devices permitted
    if (isUnlimited) {
      devices.push(incomingDeviceId);
      if (devices.length > 50) devices.shift(); // retain up to 50 recent devices for auditing
      userRecord.devices = devices;
      userRecord.device_id = incomingDeviceId;
      userRecord.bound_at = userRecord.bound_at || Math.floor(Date.now() / 1000);
      return {
        ok: true,
        updatedRecord: userRecord
      };
    }

    // Case 3: Slot available (devices.length < maxDevices)
    if (devices.length < maxDevices) {
      devices.push(incomingDeviceId);
      userRecord.devices = devices;
      userRecord.device_id = incomingDeviceId;
      userRecord.bound_at = userRecord.bound_at || Math.floor(Date.now() / 1000);
      return {
        ok: true,
        updatedRecord: userRecord
      };
    }

    // Case 4: Device capacity exceeded
    if (maxDevices === 1) {
      return {
        ok: false,
        errorFa: 'این کد اشتراک قبلاً روی دستگاه دیگری فعال شده است (مجاز برای ۱ گوشی). جهت اتصال روی این دستگاه، با پشتیبانی تماس بگیرید.'
      };
    } else {
      return {
        ok: false,
        errorFa: `ظرفیت دستگاه‌های مجاز برای این اشتراک تکمیل شده است (حداکثر ${maxDevices} گوشی). جهت اتصال روی دستگاه جدید با پشتیبانی تماس بگیرید.`
      };
    }
  }

  static resetLock(userRecord) {
    userRecord.device_id = null;
    userRecord.devices = [];
    userRecord.bound_at = null;
    return userRecord;
  }
}
