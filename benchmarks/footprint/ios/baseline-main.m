#import <KwasmFootprintBaseline/KwasmFootprintBaseline.h>

int main(void) {
    @autoreleasepool {
        KwasmFootprintBaselineProbe *probe =
            [[KwasmFootprintBaselineProbe alloc] init];
        return [probe run] == 42 ? 0 : 1;
    }
}
