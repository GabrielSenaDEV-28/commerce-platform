import { ActivatedRouteSnapshot, BaseRouteReuseStrategy } from '@angular/router';

export class EditableRouteReuseStrategy extends BaseRouteReuseStrategy {
  override shouldReuseRoute(future: ActivatedRouteSnapshot, current: ActivatedRouteSnapshot): boolean {
    if (future.data['reloadOnParamChange'] && JSON.stringify(future.params) !== JSON.stringify(current.params)) return false;
    return super.shouldReuseRoute(future, current);
  }
}
