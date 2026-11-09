"""
Subscription and Renewal Plans for AsanVPN
Configured for:
10 GB   70T
20 GB   130T
30 GB   190T
40 GB   260T
"""

DEFAULT_PLANS_DICT = {
    "plan_10g": {
        "id": "plan_10g",
        "title": "۱۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 10,
        "max_devices": 2,
        "price_toman": 70000,
        "description": "۳۰ روز اعتبار | ۱۰ گیگابایت ترافیک | ۲ دستگاه همزمان (۲ کاربر)"
    },
    "plan_20g": {
        "id": "plan_20g",
        "title": "۲۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 20,
        "max_devices": 2,
        "price_toman": 130000,
        "description": "۳۰ روز اعتبار | ۲۰ گیگابایت ترافیک | ۲ دستگاه همزمان (۲ کاربر)"
    },
    "plan_30g": {
        "id": "plan_30g",
        "title": "۳۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 30,
        "max_devices": 2,
        "price_toman": 190000,
        "description": "۳۰ روز اعتبار | ۳۰ گیگابایت ترافیک | ۲ دستگاه همزمان (۲ کاربر)"
    },
    "plan_40g": {
        "id": "plan_40g",
        "title": "۴۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 40,
        "max_devices": 2,
        "price_toman": 260000,
        "description": "۳۰ روز اعتبار | ۴۰ گیگابایت ترافیک | ۲ دستگاه همزمان (۲ کاربر)"
    }
}

RENEWAL_OPTIONS = {
    "renew_10g": {
        "id": "renew_10g",
        "title": "تمدید ۱۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 10,
        "price_toman": 70000,
        "description": "افزایش ۳۰ روز اعتبار و ۱۰ گیگ حجم"
    },
    "renew_20g": {
        "id": "renew_20g",
        "title": "تمدید ۲۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 20,
        "price_toman": 130000,
        "description": "افزایش ۳۰ روز اعتبار و ۲۰ گیگ حجم"
    },
    "renew_30g": {
        "id": "renew_30g",
        "title": "تمدید ۳۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 30,
        "price_toman": 190000,
        "description": "افزایش ۳۰ روز اعتبار و ۳۰ گیگ حجم"
    },
    "renew_40g": {
        "id": "renew_40g",
        "title": "تمدید ۴۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 40,
        "price_toman": 260000,
        "description": "افزایش ۳۰ روز اعتبار و ۴۰ گیگ حجم"
    }
}
