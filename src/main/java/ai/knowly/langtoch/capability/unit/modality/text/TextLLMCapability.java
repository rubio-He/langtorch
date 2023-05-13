package ai.knowly.langtoch.capability.unit.modality.text;

import ai.knowly.langtoch.capability.unit.CapabilityUnit;
import ai.knowly.langtoch.memory.Memory;
import ai.knowly.langtoch.schema.io.Input;
import ai.knowly.langtoch.schema.io.Output;
import ai.knowly.langtoch.schema.memory.MemoryKey;
import ai.knowly.langtoch.schema.memory.MemoryValue;
import java.util.Optional;

public interface TextLLMCapability<T, I extends Input, O extends Output, R>
    extends CapabilityUnit<T, R> {
  I preProcess(T inputData);

  Optional<Memory<MemoryKey, MemoryValue>> getMemory();

  R postProcess(O outputData);
}
