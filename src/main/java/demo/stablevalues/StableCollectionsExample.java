package demo.stablevalues;

import java.lang.StableValue;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StableValuesDemo
{
    public static void main(String[] args)
    {
        // Fixed size: each element is computed when first accessed.
        List<String> items = StableValue.list(3, index -> {
            System.out.println("Computing index " + index);
            return "Item-" + index;
        });

        System.out.println(items.get(1)); // Computes and caches Item-1
        System.out.println(items.get(1)); // Returns cached Item-1

        // Fixed keys: each value is computed when first accessed.
        Map<String, Integer> lengths = StableValue.map(
                Set.of("apple", "banana"),
                key -> {
                    System.out.println("Computing key " + key);
                    return key.length();
                }
        );

        System.out.println(lengths.get("apple")); // Computes and caches 5
        System.out.println(lengths.get("apple")); // Returns cached 5
    }
}