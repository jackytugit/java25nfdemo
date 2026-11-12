package demo.vectorapi;

import jdk.incubator.vector.IntVector;

import java.util.Arrays;

public class SimpleVector
{
    public static void main(String[] args)
    {
        int[] a1 = {1,2,3,4,5,6,7,8};
        int[] a2 = new int[4];
        var species = IntVector.SPECIES_128;
        var v1 = IntVector.fromArray(species,a1,0);
        var v2 = IntVector.fromArray(species,a1,4);
        v1.add(v2).intoArray(a2,0);
        System.out.println(Arrays.toString(a2));
    }
}
