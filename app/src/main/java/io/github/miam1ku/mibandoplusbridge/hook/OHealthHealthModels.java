// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Exact native point-model boundary; no host statistics or synthetic measurements. */
final class OHealthHealthModels {
    enum Kind {
        HEART("heart_rate", 1008, "com.heytap.databaseengine.model.HeartRate", "HeartRate"),
        OXYGEN("spo2", 1014,
                "com.heytap.databaseengine.model.bloodoxygensaturation.BloodOxygenSaturation",
                "BloodOxygenSaturation"),
        STRESS("stress", 1017, "com.heytap.databaseengine.model.stress.Stress", "Stress");

        final String kind;
        final int table;
        final String modelName;
        final String accessor;

        Kind(String kind, int table, String modelName, String accessor) {
            this.kind = kind;
            this.table = table;
            this.modelName = modelName;
            this.accessor = accessor;
        }

        static Kind of(HealthRecord record) {
            return switch (record.kind) {
                case "heart_rate" -> HEART;
                case "spo2" -> OXYGEN;
                case "stress" -> STRESS;
                default -> throw new IllegalArgumentException("UNSUPPORTED_IMPORT_KIND");
            };
        }

        int type(HealthRecord record) {
            if (!kind.equals(record.kind)) throw new IllegalArgumentException("IMPORT_KIND_MISMATCH");
            return switch (record.measurementMode) {
                case "continuous" -> this == OXYGEN ? 10 : 0;
                case "manual" -> this == STRESS ? 1 : 3;
                case "sleep" -> {
                    if (this == STRESS) throw new IllegalArgumentException("UNSUPPORTED_IMPORT_MODE");
                    yield this == HEART ? 5 : 1;
                }
                default -> throw new IllegalArgumentException("UNSUPPORTED_IMPORT_MODE");
            };
        }
    }

    final Kind kind;
    private final Class<?> modelClass;
    private final Constructor<?> constructor;
    private final Method setAccount, setDevice, setTime, setDisplay, setSync, setType, setValue;
    private final Method getAccount, getDevice, getTime, getType, getValue, setReliability;

    OHealthHealthModels(Kind kind, ClassLoader loader) throws ReflectiveOperationException {
        this.kind = kind;
        modelClass = Class.forName(kind.modelName, false, loader);
        constructor = modelClass.getConstructor();
        setAccount = modelClass.getMethod("setSsoid", String.class);
        setDevice = modelClass.getMethod("setDeviceUniqueId", String.class);
        setTime = modelClass.getMethod("setDataCreatedTimestamp", long.class);
        setDisplay = modelClass.getMethod("setDisplay", int.class);
        setSync = modelClass.getMethod("setSyncStatus", int.class);
        setType = modelClass.getMethod("set" + kind.accessor + "Type", int.class);
        setValue = modelClass.getMethod("set" + kind.accessor + "Value", int.class);
        getAccount = getter("getSsoid", String.class);
        getDevice = getter("getDeviceUniqueId", String.class);
        getTime = getter("getDataCreatedTimestamp", long.class);
        getType = getter("get" + kind.accessor + "Type", int.class);
        getValue = getter("get" + kind.accessor + "Value", int.class);
        setReliability = kind == Kind.HEART ? modelClass.getMethod("setReliability", Integer.class) : null;
    }

    private Method getter(String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = modelClass.getMethod(name);
        if (method.getReturnType() != returnType) throw new NoSuchMethodException("IMPORT_MODEL_CONTRACT");
        return method;
    }

    Object create(String account, HealthRecord record) throws ReflectiveOperationException {
        int type = kind.type(record);
        Object model = constructor.newInstance();
        setAccount.invoke(model, account);
        setDevice.invoke(model, record.deviceId);
        setTime.invoke(model, record.startMs);
        setDisplay.invoke(model, 1);
        setSync.invoke(model, 0);
        setType.invoke(model, type);
        setValue.invoke(model, record.value.intValue());
        if (setReliability != null) setReliability.invoke(model, Integer.valueOf(1));
        return model;
    }


    record Point(String account, String device, long timestamp, int type, int value) { }

    Point key(String account, HealthRecord record) {
        return new Point(account, record.deviceId, record.startMs, kind.type(record), record.value.intValue());
    }

    Point key(Object model) throws ReflectiveOperationException {
        if (!modelClass.isInstance(model)) throw new IllegalArgumentException("IMPORT_READ_MODEL_CONTRACT");
        return new Point((String) getAccount.invoke(model), (String) getDevice.invoke(model),
                (Long) getTime.invoke(model), (Integer) getType.invoke(model), (Integer) getValue.invoke(model));
    }
}
