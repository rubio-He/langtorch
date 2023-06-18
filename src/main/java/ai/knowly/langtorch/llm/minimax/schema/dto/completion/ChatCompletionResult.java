package ai.knowly.langtorch.llm.minimax.schema.dto.completion;

import ai.knowly.langtorch.llm.minimax.schema.dto.BaseResp;
import ai.knowly.langtorch.schema.io.Input;
import ai.knowly.langtorch.schema.io.Output;
import ai.knowly.langtorch.store.memory.MemoryValue;
import java.util.List;
import lombok.Data;

/** Object containing a response from the chat completions api. */
@Data
public class ChatCompletionResult {

  /** Request initiation time，Unixtime，Nanosecond */
  private Long created;

  /** Request the specified model */
  private String model;

  /** Recommended Best Result */
  private String reply;

  /** Input hit sensitive words */
  private Boolean inputSensitive;

  /**
   * Enter the type of hit sensitive word, and when inputSensitive is true, the return value is one
   * of the following: 1. Serious violation 2. Pornography 3. Advertising 4. Prohibition 5. Abuse 6.
   * Violence 7. Others
   */
  private Long inputSensitiveType;

  /** Output hit sensitive words */
  private Boolean outputSensitive;

  /**
   * Output hit sensitive word type, when outputSensitive is true, returns the same value as
   * inputSensitiveType
   */
  private Long outputSensitiveType;

  /** All results, quantity<=4 */
  private List<Choices> choices;

  /** Usage of tokens */
  private Usage usage;

  private BaseResp baseResp;

  @Data
  public static class Choices implements Input, Output, MemoryValue {

    /** text results */
    private String text;

    /** ranking */
    private Long index;

    /** score */
    private Float logprobes;

    /**
     * End reason, enumeration value stop: API returned the complete result generated by the model
     * length: The model generated result exceeds tokens_ To_ The length of the generate, the
     * content is truncated
     */
    private String finishReason;

    /**
     * Reply to the text's emotional prediction, with values ranging from one of the following
     * eight: sadness, embarrassment, happiness, surprise, anger, panic, confusion, and confusion
     */
    private String emotion;

    /**
     * When request.stream is true and in streaming mode, the reply text is returned in batches
     * through delta. The delta of the last reply is empty, and sensitive word detection is
     * performed on the overall reply
     */
    private String delta;
  }

  @Data
  public static class Usage {

    /**
     * The total number of consumed tokens, including input and output; The specific calculation
     * method is input tokens+maximum output tokens x beam_width uses token as the basic unit to
     * understand input and output
     *
     * <p>Assuming beam_width is 2, the input tokens are 100, and the output results are 20 tokens
     * and 30 tokens, respectively. The final consumption is 160 tokens
     */
    private Long totalTokens;
  }
}
