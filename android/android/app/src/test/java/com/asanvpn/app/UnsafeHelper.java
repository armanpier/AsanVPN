package com.asanvpn.app;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class UnsafeHelper {
    private static final Object UNSAFE;
    private static final Method STATIC_FIELD_BASE;
    private static final Method STATIC_FIELD_OFFSET;
    private static final Method PUT_OBJECT;
    private static final Method ALLOCATE_INSTANCE;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = f.get(null);

            STATIC_FIELD_BASE = unsafeClass.getMethod("staticFieldBase", Field.class);
            STATIC_FIELD_OFFSET = unsafeClass.getMethod("staticFieldOffset", Field.class);
            PUT_OBJECT = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            ALLOCATE_INSTANCE = unsafeClass.getMethod("allocateInstance", Class.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setStaticObject(Field field, Object value) {
        try {
            Object base = STATIC_FIELD_BASE.invoke(UNSAFE, field);
            long offset = (Long) STATIC_FIELD_OFFSET.invoke(UNSAFE, field);
            PUT_OBJECT.invoke(UNSAFE, base, offset, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T allocate(Class<T> clazz) {
        try {
            return (T) ALLOCATE_INSTANCE.invoke(UNSAFE, clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
