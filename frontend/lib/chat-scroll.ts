export type PrependScrollSnapshot = {
  previousScrollHeight: number;
  previousScrollTop: number;
};

export function scrollTopAfterPrepend({
  previousScrollHeight,
  previousScrollTop,
  nextScrollHeight,
}: PrependScrollSnapshot & { nextScrollHeight: number }) {
  return Math.max(0, previousScrollTop + nextScrollHeight - previousScrollHeight);
}
