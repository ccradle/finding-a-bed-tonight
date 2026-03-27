import io.karatelabs.junit6.Karate;

/**
 * Dedicated Karate runner for @observability-tagged features.
 * Runs sequential (parallel(1)) because trace polling is inherently sequential.
 *
 * Run locally:  mvn test -Dtest=ObservabilityRunnerTest -Dkarate.env=local
 * Skip in CI:   mvn test -Dkarate.options="--tags ~@observability"
 */
class ObservabilityRunnerTest {

    @Karate.Test
    Karate testObservability() {
        return Karate.run("classpath:observability")
                .tags("@observability")
                .relativeTo(getClass());
    }
}
