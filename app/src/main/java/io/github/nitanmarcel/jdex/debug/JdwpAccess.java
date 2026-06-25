package io.github.nitanmarcel.jdex.debug;

import io.github.skylot.jdwp.JDWP;

final class JdwpAccess {

    private JdwpAccess() {
    }

    static void setLocation(JDWP.EventRequest.Set.LocationOnlyRequest req, int tag, long classID, long methodID, long index) {
        req.loc.tag = tag;
        req.loc.classID = classID;
        req.loc.methodID = methodID;
        req.loc.index = index;
    }

    static long bpClassID(JDWP.Event.Composite.BreakpointEvent e) {
        return e.location.classID;
    }

    static long bpMethodID(JDWP.Event.Composite.BreakpointEvent e) {
        return e.location.methodID;
    }

    static long bpIndex(JDWP.Event.Composite.BreakpointEvent e) {
        return e.location.index;
    }

    static long stepClassID(JDWP.Event.Composite.SingleStepEvent e) {
        return e.location.classID;
    }

    static long stepMethodID(JDWP.Event.Composite.SingleStepEvent e) {
        return e.location.methodID;
    }

    static long stepIndex(JDWP.Event.Composite.SingleStepEvent e) {
        return e.location.index;
    }

    static long frameClassID(JDWP.ThreadReference.Frames.FramesReplyDataFrames f) {
        return f.location.classID;
    }

    static long frameMethodID(JDWP.ThreadReference.Frames.FramesReplyDataFrames f) {
        return f.location.methodID;
    }

    static long frameIndex(JDWP.ThreadReference.Frames.FramesReplyDataFrames f) {
        return f.location.index;
    }

    static long thisObjectID(JDWP.StackFrame.ThisObject.ThisObjectReplyData d) {
        return d.objectThis.objectID;
    }

    static long excClassID(JDWP.Event.Composite.ExceptionEvent e) {
        return e.location.classID;
    }

    static long excMethodID(JDWP.Event.Composite.ExceptionEvent e) {
        return e.location.methodID;
    }

    static long excIndex(JDWP.Event.Composite.ExceptionEvent e) {
        return e.location.index;
    }

    static int arrayTag(JDWP.ArrayReference.GetValues.GetValuesReplyData d) {
        return d.values.tag;
    }

    static java.util.List<Long> arrayValues(JDWP.ArrayReference.GetValues.GetValuesReplyData d) {
        return d.values.idOrValues;
    }

    static JDWP.ByteBuffer encodeSetRegister(JDWP jdwp, long threadID, long frameID, int slot, int tag, Object value) {
        JDWP.StackFrame.SetValues cmd = jdwp.stackFrame().cmdSetValues();
        JDWP.StackFrame.SetValues.SlotValueSetter setter = cmd.new SlotValueSetter();
        setter.slot = slot;
        setter.slotValue = jdwp.new ValuePacket();
        setter.slotValue.tag = tag;
        setter.slotValue.idOrValue = new JDWP.ByteBuffer();
        JDWP.encodeAny(setter.slotValue.idOrValue, value);
        java.util.List<JDWP.StackFrame.SetValues.SlotValueSetter> list = new java.util.ArrayList<>(1);
        list.add(setter);
        return cmd.encode(threadID, frameID, list);
    }

    static JDWP.ByteBuffer encodeSetField(JDWP jdwp, long objID, long fieldID, Object value) {
        JDWP.ObjectReference.SetValues cmd = jdwp.objectReference().cmdSetValues();
        JDWP.ObjectReference.SetValues.FieldValueSetter setter = cmd.new FieldValueSetter();
        setter.fieldID = fieldID;
        setter.value = jdwp.new UntaggedValuePacket();
        setter.value.idOrValue = new JDWP.ByteBuffer();
        JDWP.encodeAny(setter.value.idOrValue, value);
        java.util.List<JDWP.ObjectReference.SetValues.FieldValueSetter> list = new java.util.ArrayList<>(1);
        list.add(setter);
        return cmd.encode(objID, list);
    }

    static JDWP.ByteBuffer encodeSetStatic(JDWP jdwp, long typeID, long fieldID, Object value) {
        JDWP.ClassType.SetValues cmd = jdwp.classType().cmdSetValues();
        JDWP.ClassType.SetValues.SetValuesValues setter = cmd.new SetValuesValues();
        setter.fieldID = fieldID;
        setter.value = jdwp.new UntaggedValuePacket();
        setter.value.idOrValue = new JDWP.ByteBuffer();
        JDWP.encodeAny(setter.value.idOrValue, value);
        java.util.List<JDWP.ClassType.SetValues.SetValuesValues> list = new java.util.ArrayList<>(1);
        list.add(setter);
        return cmd.encode(typeID, list);
    }

    static JDWP.ByteBuffer encodeSetArrayElement(JDWP jdwp, long arrayID, int index, Object value) {
        JDWP.ArrayReference.SetValues cmd = jdwp.arrayReference().cmdSetValues();
        JDWP.ArrayReference.SetValues.SetValuesValues setter = cmd.new SetValuesValues();
        setter.value = jdwp.new UntaggedValuePacket();
        setter.value.idOrValue = new JDWP.ByteBuffer();
        JDWP.encodeAny(setter.value.idOrValue, value);
        java.util.List<JDWP.ArrayReference.SetValues.SetValuesValues> list = new java.util.ArrayList<>(1);
        list.add(setter);
        return cmd.encode(arrayID, index, list);
    }
}
