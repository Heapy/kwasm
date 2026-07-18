#import <KwasmFootprintCore/KwasmFootprintCore.h>

int main(void) {
    @autoreleasepool {
        KwasmFootprintCoreProbe *probe =
            [[KwasmFootprintCoreProbe alloc] init];
        return [probe run] == 42 ? 0 : 1;
    }
}
