#define _POSIX_C_SOURCE 200809L

#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <wasm.h>
#include <wasmtime.h>

#define WASMTIME_REFERENCE_VERSION "45.0.0"
#define WASMTIME_REFERENCE_COMMIT "377cd917af258d932d55b201a646917ecf193639"
#define WARMUP_ITERATIONS 3
#define MEASUREMENT_ITERATIONS 5
#define TARGET_ITERATION_MILLISECONDS 1000.0
#define CALIBRATION_MILLISECONDS 250.0
#define MAX_BATCH_OPERATIONS ((size_t)1000000000)
#define COREMARK_ITERATIONS_POINTER_SLOT ((size_t)812)
#define COREMARK_FIXED_ITERATIONS ((uint32_t)100)
#define COREMARK_GUEST_ELAPSED_MILLISECONDS ((int64_t)10000)

#ifndef WASMTIME_REFERENCE_ARTIFACT
#error "WASMTIME_REFERENCE_ARTIFACT must identify the pinned C API archive"
#endif

#ifndef WASMTIME_REFERENCE_ARTIFACT_SHA256
#error "WASMTIME_REFERENCE_ARTIFACT_SHA256 must identify the pinned C API archive"
#endif

typedef enum result_kind {
  RESULT_I32,
  RESULT_F32,
} result_kind_t;

typedef struct workload {
  const char *name;
  const char *path;
  const char *export_name;
  int32_t arguments[2];
  size_t argument_count;
  result_kind_t result_kind;
  int32_t expected_i32;
  float expected_f32;
  bool coremark;
  int64_t next_clock_milliseconds;
  wasmtime_store_t *store;
  wasmtime_context_t *context;
  wasmtime_func_t function;
  double compile_instantiate_milliseconds;
  size_t operations_per_sample;
  double samples[MEASUREMENT_ITERATIONS];
} workload_t;

static void fail(const char *message) {
  fprintf(stderr, "wasmtime reference runner: %s\n", message);
  exit(EXIT_FAILURE);
}

static void fail_wasmtime(const char *message, wasmtime_error_t *error,
                          wasm_trap_t *trap) {
  fprintf(stderr, "wasmtime reference runner: %s\n", message);
  wasm_byte_vec_t detail;
  if (error != NULL) {
    wasmtime_error_message(error, &detail);
    wasmtime_error_delete(error);
  } else if (trap != NULL) {
    wasm_trap_message(trap, &detail);
    wasm_trap_delete(trap);
  } else {
    fail("missing Wasmtime error detail");
  }
  fprintf(stderr, "%.*s\n", (int)detail.size, detail.data);
  wasm_byte_vec_delete(&detail);
  exit(EXIT_FAILURE);
}

static double monotonic_milliseconds(void) {
  struct timespec timestamp;
  if (clock_gettime(CLOCK_MONOTONIC, &timestamp) != 0) {
    fail("clock_gettime(CLOCK_MONOTONIC) failed");
  }
  return (double)timestamp.tv_sec * 1000.0 +
         (double)timestamp.tv_nsec / 1000000.0;
}

static uint8_t *read_file(const char *path, size_t *size) {
  FILE *file = fopen(path, "rb");
  if (file == NULL) {
    fail("cannot open a Wasm fixture");
  }
  if (fseek(file, 0, SEEK_END) != 0) {
    fail("cannot seek to the end of a Wasm fixture");
  }
  long length = ftell(file);
  if (length <= 0) {
    fail("Wasm fixture is empty or unreadable");
  }
  if (fseek(file, 0, SEEK_SET) != 0) {
    fail("cannot rewind a Wasm fixture");
  }
  uint8_t *bytes = malloc((size_t)length);
  if (bytes == NULL) {
    fail("cannot allocate a Wasm fixture buffer");
  }
  if (fread(bytes, 1, (size_t)length, file) != (size_t)length) {
    fail("cannot read a complete Wasm fixture");
  }
  if (fclose(file) != 0) {
    fail("cannot close a Wasm fixture");
  }
  *size = (size_t)length;
  return bytes;
}

static uint32_t read_u32_little_endian(const uint8_t *bytes) {
  return (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) |
         ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
}

static void write_u32_little_endian(uint8_t *bytes, uint32_t value) {
  bytes[0] = (uint8_t)value;
  bytes[1] = (uint8_t)(value >> 8);
  bytes[2] = (uint8_t)(value >> 16);
  bytes[3] = (uint8_t)(value >> 24);
}

static wasm_trap_t *coremark_clock_callback(
    void *environment, wasmtime_caller_t *caller, const wasmtime_val_t *args,
    size_t argument_count, wasmtime_val_t *results, size_t result_count) {
  (void)caller;
  (void)args;
  if (argument_count != 0 || result_count != 1) {
    fail("env.clock_ms received an unexpected signature");
  }
  workload_t *workload = environment;
  results[0].kind = WASMTIME_I64;
  results[0].of.i64 = workload->next_clock_milliseconds;
  workload->next_clock_milliseconds += COREMARK_GUEST_ELAPSED_MILLISECONDS;
  return NULL;
}

static void configure_coremark(workload_t *workload,
                               const wasmtime_instance_t *instance) {
  wasmtime_extern_t memory_export;
  if (!wasmtime_instance_export_get(workload->context, instance, "memory", 6,
                                    &memory_export) ||
      memory_export.kind != WASMTIME_EXTERN_MEMORY) {
    fail("CoreMark module does not export memory");
  }
  uint8_t *memory =
      wasmtime_memory_data(workload->context, &memory_export.of.memory);
  size_t memory_size =
      wasmtime_memory_data_size(workload->context, &memory_export.of.memory);
  if (memory_size < COREMARK_ITERATIONS_POINTER_SLOT + sizeof(uint32_t)) {
    fail("CoreMark memory is too small for the iterations pointer");
  }
  uint32_t iterations_pointer =
      read_u32_little_endian(memory + COREMARK_ITERATIONS_POINTER_SLOT);
  if ((size_t)iterations_pointer > memory_size - sizeof(uint32_t)) {
    fail("CoreMark iterations pointer is outside memory");
  }
  if (read_u32_little_endian(memory + iterations_pointer) != 0) {
    fail("CoreMark iterations must be zero before setup");
  }
  write_u32_little_endian(memory + iterations_pointer,
                          COREMARK_FIXED_ITERATIONS);
  if (read_u32_little_endian(memory + iterations_pointer) !=
      COREMARK_FIXED_ITERATIONS) {
    fail("CoreMark fixed iterations were not installed");
  }
}

static void prepare_workload(wasm_engine_t *engine, workload_t *workload) {
  size_t wasm_size = 0;
  uint8_t *wasm = read_file(workload->path, &wasm_size);
  double started = monotonic_milliseconds();

  wasmtime_module_t *module = NULL;
  wasmtime_error_t *error =
      wasmtime_module_new(engine, wasm, wasm_size, &module);
  free(wasm);
  if (error != NULL) {
    fail_wasmtime("failed to compile a Wasm fixture", error, NULL);
  }

  workload->store = wasmtime_store_new(engine, NULL, NULL);
  if (workload->store == NULL) {
    fail("failed to create a Wasmtime store");
  }
  workload->context = wasmtime_store_context(workload->store);

  wasmtime_extern_t import;
  size_t import_count = 0;
  if (workload->coremark) {
    wasm_functype_t *clock_type =
        wasm_functype_new_0_1(wasm_valtype_new_i64());
    if (clock_type == NULL) {
      fail("failed to create the CoreMark clock type");
    }
    wasmtime_func_t clock;
    wasmtime_func_new(workload->context, clock_type, coremark_clock_callback,
                      workload, NULL, &clock);
    wasm_functype_delete(clock_type);
    import.kind = WASMTIME_EXTERN_FUNC;
    import.of.func = clock;
    import_count = 1;
  }

  wasmtime_instance_t instance;
  wasm_trap_t *trap = NULL;
  error = wasmtime_instance_new(workload->context, module,
                                import_count == 0 ? NULL : &import,
                                import_count, &instance, &trap);
  wasmtime_module_delete(module);
  if (error != NULL || trap != NULL) {
    fail_wasmtime("failed to instantiate a Wasm fixture", error, trap);
  }
  workload->compile_instantiate_milliseconds =
      monotonic_milliseconds() - started;

  wasmtime_extern_t function_export;
  if (!wasmtime_instance_export_get(workload->context, &instance,
                                    workload->export_name,
                                    strlen(workload->export_name),
                                    &function_export) ||
      function_export.kind != WASMTIME_EXTERN_FUNC) {
    fail("Wasm fixture does not contain the expected function export");
  }
  workload->function = function_export.of.func;
  if (workload->coremark) {
    configure_coremark(workload, &instance);
  }
}

static void invoke_and_validate(workload_t *workload) {
  wasmtime_val_t arguments[2];
  for (size_t index = 0; index < workload->argument_count; index += 1) {
    arguments[index].kind = WASMTIME_I32;
    arguments[index].of.i32 = workload->arguments[index];
  }
  wasmtime_val_t result;
  wasm_trap_t *trap = NULL;
  wasmtime_error_t *error = wasmtime_func_call(
      workload->context, &workload->function, arguments,
      workload->argument_count, &result, 1, &trap);
  if (error != NULL || trap != NULL) {
    fail_wasmtime("Wasm fixture call failed", error, trap);
  }
  if (workload->result_kind == RESULT_I32) {
    if (result.kind != WASMTIME_I32 ||
        result.of.i32 != workload->expected_i32) {
      fail("Wasm fixture returned an unexpected i32 result");
    }
  } else if (result.kind != WASMTIME_F32 ||
             result.of.f32 != workload->expected_f32) {
    fail("CoreMark returned an unexpected score");
  }
}

static double run_batch(workload_t *workload, size_t operations) {
  double started = monotonic_milliseconds();
  for (size_t operation = 0; operation < operations; operation += 1) {
    invoke_and_validate(workload);
  }
  return monotonic_milliseconds() - started;
}

static size_t scaled_operation_count(size_t operations, double target,
                                     double elapsed) {
  if (elapsed <= 0.0) {
    if (operations > MAX_BATCH_OPERATIONS / 100) {
      return MAX_BATCH_OPERATIONS;
    }
    return operations * 100;
  }
  double scaled = (double)operations * target / elapsed;
  if (scaled < 1.0) {
    return 1;
  }
  if (scaled > (double)MAX_BATCH_OPERATIONS) {
    return MAX_BATCH_OPERATIONS;
  }
  return (size_t)(scaled + 0.5);
}

static void measure_workload(workload_t *workload) {
  size_t operations = 1;
  double elapsed = 0.0;
  for (int attempt = 0; attempt < 12; attempt += 1) {
    elapsed = run_batch(workload, operations);
    if (elapsed >= CALIBRATION_MILLISECONDS ||
        operations == MAX_BATCH_OPERATIONS) {
      break;
    }
    size_t next =
        scaled_operation_count(operations, CALIBRATION_MILLISECONDS, elapsed);
    if (next <= operations) {
      next = operations > MAX_BATCH_OPERATIONS / 2
                 ? MAX_BATCH_OPERATIONS
                 : operations * 2;
    }
    operations = next;
  }
  operations = scaled_operation_count(operations,
                                      TARGET_ITERATION_MILLISECONDS, elapsed);

  for (int warmup = 0; warmup < WARMUP_ITERATIONS; warmup += 1) {
    elapsed = run_batch(workload, operations);
    operations = scaled_operation_count(
        operations, TARGET_ITERATION_MILLISECONDS, elapsed);
  }
  workload->operations_per_sample = operations;
  for (int iteration = 0; iteration < MEASUREMENT_ITERATIONS; iteration += 1) {
    elapsed = run_batch(workload, operations);
    workload->samples[iteration] = elapsed / (double)operations;
  }
}

static double mean_score(const workload_t *workload) {
  double total = 0.0;
  for (int iteration = 0; iteration < MEASUREMENT_ITERATIONS; iteration += 1) {
    total += workload->samples[iteration];
  }
  return total / (double)MEASUREMENT_ITERATIONS;
}

static void write_report(const char *output_path, const char *target,
                         double engine_initialization_milliseconds,
                         const workload_t *workloads, size_t workload_count) {
  FILE *output = fopen(output_path, "w");
  if (output == NULL) {
    fail("cannot create the raw Wasmtime report");
  }
  fprintf(output,
          "{\n"
          "  \"schemaVersion\": 1,\n"
          "  \"kind\": \"wasmtime-compiled-reference-raw\",\n"
          "  \"runtime\": \"wasmtime\",\n"
          "  \"version\": \"%s\",\n"
          "  \"upstreamCommit\": \"%s\",\n"
          "  \"engine\": \"cranelift\",\n"
          "  \"optimization\": \"speed\",\n"
          "  \"artifact\": \"%s\",\n"
          "  \"artifactSha256\": \"%s\",\n"
          "  \"target\": \"%s\",\n"
          "  \"warmupIterations\": %d,\n"
          "  \"measurementIterations\": %d,\n"
          "  \"targetIterationMilliseconds\": %.0f,\n"
          "  \"engineInitializationMs\": %.9f,\n"
          "  \"measurements\": [\n",
          WASMTIME_REFERENCE_VERSION, WASMTIME_REFERENCE_COMMIT,
          WASMTIME_REFERENCE_ARTIFACT, WASMTIME_REFERENCE_ARTIFACT_SHA256,
          target, WARMUP_ITERATIONS, MEASUREMENT_ITERATIONS,
          TARGET_ITERATION_MILLISECONDS, engine_initialization_milliseconds);
  for (size_t index = 0; index < workload_count; index += 1) {
    const workload_t *workload = &workloads[index];
    fprintf(output,
            "    {\"workload\": \"%s\", \"scoreMsPerOp\": %.12f, "
            "\"compileInstantiateMs\": %.9f, "
            "\"operationsPerSample\": %zu, \"samplesMsPerOp\": [",
            workload->name, mean_score(workload),
            workload->compile_instantiate_milliseconds,
            workload->operations_per_sample);
    for (int iteration = 0; iteration < MEASUREMENT_ITERATIONS;
         iteration += 1) {
      fprintf(output, "%s%.12f", iteration == 0 ? "" : ", ",
              workload->samples[iteration]);
    }
    fprintf(output, "]}%s\n", index + 1 == workload_count ? "" : ",");
  }
  fprintf(output, "  ]\n}\n");
  if (fclose(output) != 0) {
    fail("cannot close the raw Wasmtime report");
  }
}

int main(int argument_count, char **arguments) {
  if (argument_count != 8) {
    fprintf(stderr,
            "usage: %s OUTPUT TARGET FIB_WASM SHA_WASM JSON_WASM "
            "COREMARK_WASM RESERVED\n",
            arguments[0]);
    return EXIT_FAILURE;
  }
  if (strcmp(arguments[7], "fixed-coremark-100") != 0) {
    fail("the CoreMark protocol marker is missing");
  }
  if (strcmp(arguments[2], "jvm") != 0 &&
      strcmp(arguments[2], "macosArm64") != 0 &&
      strcmp(arguments[2], "linuxArm64") != 0 &&
      strcmp(arguments[2], "linuxX64") != 0) {
    fail("unsupported benchmark target");
  }

  workload_t workloads[] = {
      {.name = "coremark",
       .path = arguments[6],
       .export_name = "run",
       .argument_count = 0,
       .result_kind = RESULT_F32,
       .expected_f32 = 10.0f,
       .coremark = true},
      {.name = "fib35",
       .path = arguments[3],
       .export_name = "fib",
       .arguments = {35, 0},
       .argument_count = 1,
       .result_kind = RESULT_I32,
       .expected_i32 = 9227465},
      {.name = "json",
       .path = arguments[5],
       .export_name = "parse_json",
       .arguments = {253, 0},
       .argument_count = 1,
       .result_kind = RESULT_I32,
       .expected_i32 = -656560826},
      {.name = "sha256",
       .path = arguments[4],
       .export_name = "sha256_loop",
       .arguments = {16384, 0x6A09E667},
       .argument_count = 2,
       .result_kind = RESULT_I32,
       .expected_i32 = -466365695},
  };

  wasm_config_t *config = wasm_config_new();
  if (config == NULL) {
    fail("failed to create a Wasmtime configuration");
  }
  wasmtime_config_strategy_set(config, WASMTIME_STRATEGY_CRANELIFT);
  wasmtime_config_cranelift_opt_level_set(config, WASMTIME_OPT_LEVEL_SPEED);
  double engine_started = monotonic_milliseconds();
  wasm_engine_t *engine = wasm_engine_new_with_config(config);
  double engine_initialization_milliseconds =
      monotonic_milliseconds() - engine_started;
  if (engine == NULL) {
    fail("failed to create a Cranelift Wasmtime engine");
  }

  size_t workload_count = sizeof(workloads) / sizeof(workloads[0]);
  for (size_t index = 0; index < workload_count; index += 1) {
    prepare_workload(engine, &workloads[index]);
    invoke_and_validate(&workloads[index]);
    measure_workload(&workloads[index]);
  }
  write_report(arguments[1], arguments[2], engine_initialization_milliseconds,
               workloads, workload_count);

  for (size_t index = 0; index < workload_count; index += 1) {
    wasmtime_store_delete(workloads[index].store);
  }
  wasm_engine_delete(engine);
  return EXIT_SUCCESS;
}
