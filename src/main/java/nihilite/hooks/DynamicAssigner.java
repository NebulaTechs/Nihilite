package nihilite.hooks;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.assign.TypeCasting;


public final class DynamicAssigner implements Assigner {

    public static final DynamicAssigner INSTANCE = new DynamicAssigner();

    private DynamicAssigner() {}

    @Override
    public StackManipulation assign(TypeDescription.Generic source,
                                    TypeDescription.Generic target,
                                    Typing typing) {
        if (source.equals(target)
                || source.asErasure().isAssignableTo(target.asErasure())) {
            return StackManipulation.Trivial.INSTANCE;
        }
        return TypeCasting.to(target);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DynamicAssigner;
    }

    @Override
    public int hashCode() {
        return DynamicAssigner.class.hashCode();
    }
}