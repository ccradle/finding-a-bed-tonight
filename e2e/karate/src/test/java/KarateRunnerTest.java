import io.karatelabs.junit6.Karate;

class KarateRunnerTest {

    @Karate.Test
    Karate testAll() {
        return Karate.run("classpath:features").relativeTo(getClass());
    }
}
