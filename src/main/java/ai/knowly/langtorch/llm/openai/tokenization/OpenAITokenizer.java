package ai.knowly.langtorch.llm.openai.tokenization;

import ai.knowly.langtorch.llm.openai.util.OpenAIModel;
import ai.knowly.langtorch.schema.chat.ChatMessage;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;

/**
 * Tokenizer for OpenAI models. It's currently not used as it's provided by OpenAI rest response.
 * Will need this when we support streaming response.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OpenAITokenizer {
  private static final ImmutableList<OpenAIModel> GPT_3_MODELS =
      ImmutableList.of(
          OpenAIModel.GPT_3_5_TURBO,
          OpenAIModel.GPT_3_5_TURBO_16K,
          OpenAIModel.GPT_3_5_TURBO_0613,
          OpenAIModel.GPT_3_5_TURBO_16K_0613);

  private static final ImmutableList<OpenAIModel> GPT_4_MODELS =
      ImmutableList.of(
          OpenAIModel.GPT_4,
          OpenAIModel.GPT_4_0613,
          OpenAIModel.GPT_4_32K,
          OpenAIModel.GPT_4_32K_0613);

  /**
   * This Java function encodes a given text using a specified OpenAI model and returns the generated embeddding.
   * 
   * @param model The model parameter is an instance of the OpenAIModel class, which represents the
   * OpenAI language model being used for encoding the text.
   * @param text The text parameter is a string that represents the input text that needs to be encoded
   * using the specified OpenAIModel.
   * @return Embedding: a List of Integers is being returned.
   */
  public static List<Integer> encode(OpenAIModel model, String text) {
    return Objects.requireNonNull(Encodings.ENCODING_BY_MODEL.get(model)).encode(text);
  }

  /**
   * This Java function decodes the generated embedding back to text using a specified OpenAI model.
   * 
   * @param model The parameter "model" is an instance of the OpenAIModel class, which represents a
   * pre-trained language model provided by OpenAI. It is used to decode a list of integer tokens into
   * a human-readable string.
   * @param tokens The `tokens` parameter is a list of integers representing a sequence of tokens.
   * These tokens are typically generated by a language model and can be used to represent words,
   * phrases, or other units of text. The `decode` method takes these tokens and returns a string
   * representation of the original text
   * @return The method is returning a decoded string.
   */
  public static String decode(OpenAIModel model, List<Integer> tokens) {
    return Objects.requireNonNull(Encodings.ENCODING_BY_MODEL.get(model)).decode(tokens);
  }

  /**
   * This Java function returns the number of tokens in a given text after encoding it using an OpenAI
   * model.
   * 
   * @param model The OpenAIModel object that represents the pre-trained language model used for
   * encoding the text.
   * @param text The `text` parameter is a string that represents the input text for which we want to
   * generate a token number.
   * @return The method `getTokenNumber` is returning the number of tokens generated by the OpenAI
   * model for the given input text. The `encode` method is used to generate the tokens and the
   * `size()` method is used to get the number of tokens.
   */
  public static long getTokenNumber(OpenAIModel model, String text) {
    return encode(model, text).size();
  }

  /**
   * The function calculates the number of tokens in a list of chat messages based on the OpenAI model.
   * 
   * @param model The OpenAI model being used for generating text.
   * @param messages A list of ChatMessage objects representing the conversation messages.
   * @return The method is returning a long value which represents the total number of tokens in the
   * given list of ChatMessage objects.
   * 
   * The algorithm for counting tokens is based on  https://github.com/openai/openai-cookbook/blob/main/examples/How_to_count_tokens_with_tiktoken.ipynb
   */
  public static long getTokenNumber(OpenAIModel model, List<ChatMessage> messages) {
    int tokensPerMessage = 0;
    int tokensPerName = 0;
    if (GPT_3_MODELS.contains(model)) {
      // Every message follows <|start|>{role/name}\n{content}<|end|>\n
      tokensPerMessage = 4;
      // If there's a name, the role is omitted
      tokensPerName = -1;

    } else if (GPT_4_MODELS.contains(model)) {
      // Every message follows <|start|>{role/name}\n{content}<|end|>\n
      tokensPerMessage = 3;
      // If there's a name, the role is omitted
      tokensPerName = 1;
    } else {
      throw new UnsupportedOperationException("You model is not supported yet for token counting.");
    }

    int numberOfTokens = 0;
    for (ChatMessage message : messages) {
      numberOfTokens += tokensPerMessage;
      numberOfTokens += encode(model, message.getContent()).size();
      numberOfTokens += encode(model, message.getRole().name()).size();
      numberOfTokens += encode(model, message.getName()).size() + tokensPerName;
    }
    // Every reply is primed with <|start|>assistant<|message|>
    numberOfTokens += 3;
    return numberOfTokens;
  }
}